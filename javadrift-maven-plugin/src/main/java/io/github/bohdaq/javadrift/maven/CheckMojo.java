package io.github.bohdaq.javadrift.maven;
import io.github.bohdaq.javadrift.core.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.MavenSession;
import java.io.File;
import java.nio.file.*;

@Mojo(name="check",defaultPhase=LifecyclePhase.VERIFY,aggregator=true,threadSafe=true,
      requiresDependencyResolution=ResolutionScope.COMPILE)
@Execute(phase=LifecyclePhase.COMPILE)
public final class CheckMojo extends AbstractMojo {
    @Parameter(defaultValue="${session}",readonly=true,required=true) private MavenSession session;
    @Parameter(defaultValue="${project}",readonly=true,required=true) private MavenProject project;
    @Parameter(property="javadrift.since") private String since;
    @Parameter(property="javadrift.warnOnly",defaultValue="false") private boolean warnOnly;
    @Parameter(property="javadrift.skip",defaultValue="false") private boolean skip;
    @Parameter(property="javadrift.format",defaultValue="text") private String format;
    @Parameter(property="javadrift.output") private File output;
    @Parameter(property="javadrift.config") private File configFile;
    public void execute() throws MojoExecutionException,MojoFailureException {
        if(skip || !project.equals(session.getTopLevelProject()))return;
        try {
            Path root=session.getTopLevelProject().getBasedir().toPath();
            Config config=Config.load(root,configFile==null?null:configFile.toPath());
            for(MavenProject module:session.getProjects()) {
                String classes=module.getBuild().getOutputDirectory();
                if(Files.isDirectory(Path.of(classes)))config.classes.add(classes);
                for(String entry:module.getCompileClasspathElements())
                    if(Files.exists(Path.of(entry)) && !entry.equals(classes))config.classpath.add(entry);
            }
            Analyzer.Result result=new Analyzer().analyze(root,config,since,true);
            String report=Reporters.render(result,format);
            if(output!=null)Files.writeString(output.toPath(),report);else getLog().info(report);
            if(!warnOnly && result.fails(config))throw new MojoFailureException("Javadrift found stale documentation");
        } catch(MojoFailureException e) {throw e;} catch(Exception e) {throw new MojoExecutionException("Javadrift: "+e.getMessage(),e);}
    }
}
