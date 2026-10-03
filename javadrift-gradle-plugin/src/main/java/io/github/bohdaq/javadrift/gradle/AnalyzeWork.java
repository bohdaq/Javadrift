package io.github.bohdaq.javadrift.gradle;

import io.github.bohdaq.javadrift.core.*;
import org.gradle.api.GradleException;
import org.gradle.api.file.*;
import org.gradle.api.provider.Property;
import org.gradle.api.logging.Logging;
import org.gradle.workers.*;
import java.io.File;
import java.nio.file.Files;

/** Core and its embedded Kotlin compiler are loaded only in this worker. */
public abstract class AnalyzeWork implements WorkAction<AnalyzeWork.Parameters> {
    public interface Parameters extends WorkParameters {
        DirectoryProperty getRoot();
        ConfigurableFileCollection getClasses();
        ConfigurableFileCollection getDependencies();
        Property<String> getSince();
        Property<Boolean> getWarnOnly();
        Property<String> getFormat();
        Property<String> getGroup();
        Property<String> getVersion();
        Property<String> getArtifact();
        RegularFileProperty getReport();
    }
    public void execute() {
        try {
            var p=getParameters();var root=p.getRoot().get().getAsFile().toPath();
            Config config=Config.load(root,null);
            for(File f:p.getClasses())if(f.exists())config.classes.add(f.getAbsolutePath());
            for(File f:p.getDependencies())if(f.exists())config.classpath.add(f.getAbsolutePath());
            if(config.project.groupId==null && !p.getGroup().get().isBlank() && !p.getVersion().get().equals("unspecified")) {
                config.project.groupId=p.getGroup().get();config.project.artifactId=p.getArtifact().get();config.project.version=p.getVersion().get();
            }
            Analyzer.Result result=new Analyzer().analyze(root,config,p.getSince().getOrNull(),true);
            String report=Reporters.render(result,p.getFormat().get());var target=p.getReport().get().getAsFile().toPath();
            Files.createDirectories(target.getParent());Files.writeString(target,report);
            Logging.getLogger(AnalyzeWork.class).lifecycle(report);
            if(!p.getWarnOnly().get() && result.fails(config))throw new GradleException("Javadrift found stale documentation");
        } catch(GradleException e) {throw e;}catch(Exception e){throw new GradleException("Javadrift: "+e.getMessage(),e);}
    }
}
