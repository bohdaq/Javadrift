package io.github.bohdaq.javadrift.core;

import java.nio.file.*;
import java.util.Set;

/** Resolves documentation inputs without building a documentation site. */
final class RepositoryPaths {
    private RepositoryPaths() {}
    static Path origin(Path root,Path document) throws java.io.IOException {
        if(!Files.isSymbolicLink(document))return document;
        Path realRoot=root.toRealPath(),realDocument=document.toRealPath();
        return realDocument.startsWith(realRoot)?root.resolve(realRoot.relativize(realDocument)):document;
    }
    static boolean exists(Path root,Path document,Path target,String path,boolean link) {
        if(Files.exists(target.normalize()))return true;
        // GitHub branch navigation targets repository files, including multiline Markdown links.
        var branch=java.util.regex.Pattern.compile("^(?:\\.\\./)+(?:tree|blob)/HEAD/(.+)$").matcher(path);
        if(link && branch.matches()) {
            Path file=root.resolve(branch.group(1)).normalize();return file.startsWith(root) && Files.exists(file);
        }
        // Module-wide source-directory conventions require a real matching module directory.
        if(!link && fragmentDirectory(path)) {
            try(var files=Files.walk(root)) {
                if(files.filter(Files::isDirectory).anyMatch(p->root.relativize(p).toString().replace('\\','/').endsWith("/"+path)
                    && !root.relativize(p).toString().matches("(?:.*[/\\\\])?(?:target|build|\\.git)(?:[/\\\\].*)?")))return true;
            } catch(java.io.IOException ignored) { /* Fall through to the ordinary missing-path check. */ }
        }
        String relative=root.relativize(document).toString().replace('\\','/');
        if(!link) {
            // Inline paths can be relative to the document's module or documentation workspace.
            for(Path base=document.getParent();base!=null && base.startsWith(root);base=base.getParent())
                if(Files.exists(base.resolve(path).normalize()))return true;
        }
        if(link && relative.toLowerCase(java.util.Locale.ROOT).matches("\\.github/(?:pull_request_template\\.md|pull_request_template/.*\\.md|issue_template/.*\\.md)"))
            if(Files.exists(root.resolve(path).normalize()))return true;
        boolean site=relative.startsWith("src/site/") || relative.contains("/src/site/");
        if(!site)return false;
        if(path.endsWith(".html")) {
            String stem=target.toString().substring(0,target.toString().length()-5);
            if(Files.exists(Path.of(stem+".md"))||Files.exists(Path.of(stem+".adoc")))return true;
        }
        String route=path.replaceFirst("^(?:\\.\\./)+","");
        Path module=root;String resource;
        if(route.startsWith("javadoc/")) {
            String[] parts=route.split("/",3);if(parts.length!=3)return false;
            module=root.resolve(parts[1]);resource=parts[2];
        } else if(route.startsWith("apidocs/")) {
            // Maven site output belongs to the nearest module containing this document.
            for(Path base=document.getParent();base!=null && base.startsWith(root);base=base.getParent())
                if(Files.exists(base.resolve("pom.xml"))){module=base;break;}
            resource=route.substring("apidocs/".length());
        } else return false;
        if(!module.normalize().startsWith(root))return false;
        if(resource.equals("index.html"))return Files.exists(module.resolve("pom.xml"));
        Path file=Path.of(resource);Path parent=file.getParent();String folder=parent==null?"":parent+"/";
        if(file.getFileName().toString().equals("package-summary.html"))return Files.isDirectory(module.resolve("src/main/java/"+folder));
        if(!resource.endsWith(".html"))return false;
        // Nested-class pages share the outer class's source file. Anchors are outside JD007.
        String name=file.getFileName().toString().replaceFirst("\\.html$","").split("\\.")[0];
        Path source=module.resolve("src/main/java/"+folder+name+".java").normalize();
        return source.startsWith(root) && Files.isRegularFile(source);
    }
    private static boolean fragmentDirectory(String path) {
        return Set.of("src/main","src/test","src/testFixtures").contains(path);
    }
    static boolean ambiguousInline(Path root,Path document,DocReader.Fragment fragment,String path) {
        if(fragment.kind()!=DocReader.Kind.CODE || document.getParent().equals(root))return false;
        String file=Path.of(path).getFileName().toString();
        // Bare build filenames and directory mentions often describe the consumer's project.
        return !file.contains(".") || Set.of("pom.xml","build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts").contains(path);
    }
}
