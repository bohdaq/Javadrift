package io.github.bohdaq.javadrift.gradle;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

/** Track refs even when the checkout uses a linked worktree or packed refs. */
final class GitInputs {
    static String state(Path root) throws IOException {
        var builder=new FileRepositoryBuilder().findGitDir(root.toFile());
        if(builder.getGitDir()==null)return "no-git";
        try(var repo=builder.build()) {
            var values=new ArrayList<String>();
            ObjectId head=repo.resolve("HEAD");values.add(head==null?"unborn":head.name());
            repo.getRefDatabase().getRefsByPrefix("refs/").forEach(r->{
                if(r.getObjectId()!=null)values.add(r.getName()+":"+r.getObjectId().name());
            });
            Collections.sort(values);return String.join("\n",values);
        }
    }
}
