package io.github.bohdaq.javadrift.cli;
import io.github.bohdaq.javadrift.core.*;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

@Command(name="javadrift",mixinStandardHelpOptions=true,version="Javadrift 0.2.0",
        description="Offline stale documentation detection for JVM projects",subcommands={Main.CheckCommand.class,Main.BaselineCommand.class,Main.Explain.class})
public final class Main implements Runnable {
    public void run() {new CommandLine(this).usage(System.out);}
    public static CommandLine commandLine() {
        return new CommandLine(new Main()).setExecutionExceptionHandler((e,cmd,parse)->{
            cmd.getErr().println("javadrift: "+e.getMessage());return 2;
        });
    }
    public static void main(String[] args) {System.exit(commandLine().execute(args));}
    @Command(name="check",mixinStandardHelpOptions=true,description="Check project documentation")
    public static final class CheckCommand implements Callable<Integer> {
        @Option(names={"--root","-r"},description="Project directory",defaultValue=".") Path root;
        @Option(names="--config",description="YAML configuration file") Path configPath;
        @Option(names="--warn-only",description="Report findings without failing") boolean warnOnly;
        @Option(names="--output",description="Write report to a file") Path output;
        @Option(names="--class-dir",description="Compiled project classes (repeatable)") java.util.List<String> classes=new java.util.ArrayList<>();
        @Option(names="--classpath",description="Dependency classpath using the platform path separator") String classpath;
        @Option(names="--since",description="Compare source symbols at a Git ref with HEAD") String since;
        @Option(names="--format",defaultValue="text",description="text, json, github or sarif") String format;
        @Spec Model.CommandSpec spec;
        public Integer call() throws Exception {
            Config config=Config.load(root,configPath);
            config.classes.addAll(classes);
            if(classpath!=null)config.classpath.addAll(java.util.Arrays.asList(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))));
            Analyzer.Result result=new Analyzer().analyze(root,config,since,true);
            String report=Reporters.render(result,format);
            if(output==null) {spec.commandLine().getOut().print(report);spec.commandLine().getOut().flush();} else {
                Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,report);
            }
            return !warnOnly && result.fails(config)?1:0;
        }
    }
    @Command(name="baseline",mixinStandardHelpOptions=true,description="Accept, audit or prune baseline findings")
    public static final class BaselineCommand implements Callable<Integer> {
        @Option(names={"--root","-r"},defaultValue=".") Path root;
        @Option(names="--config") Path configPath;
        @Option(names="--since") String since;
        @Option(names="--output",description="Override baseline path") Path output;
        @Option(names="--class-dir",description="Compiled project classes (repeatable)") java.util.List<String> classes=new java.util.ArrayList<>();
        @Option(names="--classpath",description="Dependency classpath using the platform path separator") String classpath;
        @ArgGroup(exclusive=true,multiplicity="0..1") Maintenance maintenance;
        static class Maintenance {
            @Option(names="--check",description="Report unused entries without changing the baseline; exit 1 if any") boolean check;
            @Option(names="--prune",description="Remove unused entries without accepting new findings") boolean prune;
        }
        @Spec Model.CommandSpec spec;
        public Integer call() throws Exception {
            Config config=Config.load(root,configPath);
            config.classes.addAll(classes);
            if(classpath!=null)config.classpath.addAll(java.util.Arrays.asList(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))));
            Analyzer.Result result=new Analyzer().analyze(root,config,since,false);
            Path target=output==null?root.resolve(config.baseline):output;
            if(maintenance!=null) {
                Baseline.Audit audit=Baseline.audit(target,result.findings());
                if(maintenance.prune)Baseline.prune(target,audit);
                spec.commandLine().getOut().println((maintenance.prune?"Pruned ":"Found ")+audit.unused().size()+" unused baseline entries in "+target);
                if(maintenance.check)for(String fingerprint:audit.unused())spec.commandLine().getOut().println("Unused: "+fingerprint);
                spec.commandLine().getOut().println("Retained "+audit.retained().size()+" accepted fingerprints; "+audit.unaccepted().size()+" current fingerprints remain unaccepted.");
                return maintenance.check && !audit.unused().isEmpty()?1:0;
            }
            Baseline.write(target,result.findings());
            spec.commandLine().getOut().println("Accepted "+result.findings().size()+" findings in "+target);
            return 0;
        }
    }
    @Command(name="explain",mixinStandardHelpOptions=true,description="Describe a check")
    public static final class Explain implements Callable<Integer> {
        @Parameters(index="0",description="Check ID or name") String id;
        @Spec Model.CommandSpec spec;
        public Integer call() {
            Check check=Check.from(id);
            spec.commandLine().getOut().println(check.name()+" ("+check.key+", default "+check.defaultSeverity.name().toLowerCase()+"): "+check.explanation);
            return 0;
        }
    }
}
