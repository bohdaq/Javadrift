package io.github.bohdaq.javadrift.cli;
import io.github.bohdaq.javadrift.core.*;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

@Command(name="javadrift",mixinStandardHelpOptions=true,version="Javadrift 0.1.0-SNAPSHOT",
        description="Offline stale documentation detection for JVM projects",subcommands={Main.CheckCommand.class,Main.Explain.class})
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
        @Spec Model.CommandSpec spec;
        public Integer call() throws Exception {
            Config config=Config.load(root,configPath);
            Analyzer.Result result=new Analyzer().analyze(root,config);
            String report=Reporters.text(result);
            if(output==null) {spec.commandLine().getOut().print(report);spec.commandLine().getOut().flush();} else Files.writeString(output,report);
            return !warnOnly && result.fails(config)?1:0;
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
