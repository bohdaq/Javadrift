package io.github.bohdaq.javadrift.gradle;
import io.github.bohdaq.javadrift.core.*;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import java.io.File;
import java.nio.file.*;

public abstract class JavadriftCheck extends DefaultTask {
    @Internal public abstract DirectoryProperty getRoot();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getDocuments();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getSources();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getConfiguration();
    @Classpath public abstract ConfigurableFileCollection getClassDirectories();
    @Classpath public abstract ConfigurableFileCollection getDependencyClasspath();
    @Input @Optional public abstract Property<String> getSince();
    @Input public abstract Property<Boolean> getWarnOnly();
    @Input public abstract Property<String> getFormat();
    @Input public abstract Property<String> getGitState();
    @Input public abstract Property<String> getProjectGroup();
    @Input public abstract Property<String> getProjectVersion();
    @Input public abstract Property<String> getProjectArtifact();
    @OutputFile public abstract RegularFileProperty getReport();
    public JavadriftCheck() {
        // Git refs/tags and baselines can change independently of compiled inputs.
        getOutputs().upToDateWhen(task->!getSince().isPresent());
    }
    @TaskAction public void analyze() {
        try {
            Path root=getRoot().get().getAsFile().toPath();Config config=Config.load(root,null);
            for(File f:getClassDirectories())if(f.exists())config.classes.add(f.getAbsolutePath());
            for(File f:getDependencyClasspath())if(f.exists())config.classpath.add(f.getAbsolutePath());
            if(config.project.groupId==null && !getProjectGroup().get().isBlank() && !getProjectVersion().get().equals("unspecified")) {
                config.project.groupId=getProjectGroup().get();config.project.artifactId=getProjectArtifact().get();config.project.version=getProjectVersion().get();
            }
            Analyzer.Result result=new Analyzer().analyze(root,config,getSince().getOrNull(),true);
            String report=Reporters.render(result,getFormat().get());Path target=getReport().get().getAsFile().toPath();
            Files.createDirectories(target.getParent());Files.writeString(target,report);getLogger().lifecycle(report);
            if(!getWarnOnly().get() && result.fails(config))throw new GradleException("Javadrift found stale documentation");
        } catch(GradleException e) {throw e;}catch(Exception e){throw new GradleException("Javadrift: "+e.getMessage(),e);}
    }
}
