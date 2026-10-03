package io.github.bohdaq.javadrift.gradle;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import javax.inject.Inject;
import org.gradle.workers.WorkerExecutor;

public abstract class JavadriftCheck extends DefaultTask {
    @Internal public abstract DirectoryProperty getRoot();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getDocuments();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getSources();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getConfiguration();
    @Classpath public abstract ConfigurableFileCollection getClassDirectories();
    @Classpath public abstract ConfigurableFileCollection getDependencyClasspath();
    @Classpath public abstract ConfigurableFileCollection getEngineClasspath();
    @Inject protected abstract WorkerExecutor getWorkerExecutor();
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
        var queue=getWorkerExecutor().classLoaderIsolation(spec->spec.getClasspath().from(getEngineClasspath()));
        queue.submit(AnalyzeWork.class,p->{
            p.getRoot().set(getRoot());p.getClasses().from(getClassDirectories());
            p.getDependencies().from(getDependencyClasspath());p.getSince().set(getSince());
            p.getWarnOnly().set(getWarnOnly());p.getFormat().set(getFormat());
            p.getGroup().set(getProjectGroup());p.getVersion().set(getProjectVersion());
            p.getArtifact().set(getProjectArtifact());p.getReport().set(getReport());
        });
        queue.await();
    }
}
