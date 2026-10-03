package io.github.bohdaq.javadrift.core;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class SourceIndexer {
    private final JavaParser parser=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    public SymbolIndex index(Path root) throws IOException {
        SymbolIndex index=new SymbolIndex();
        try(var files=Files.walk(root)) {
            for(Path file:files.filter(Files::isRegularFile).filter(p->productionJava(root.relativize(p).toString().replace('\\','/'))).sorted().toList())
                add(index,Files.readString(file),root.relativize(file).toString());
        }
        return index;
    }
    public static boolean productionJava(String path) {
        return path.endsWith(".java") && (path.startsWith("src/") || path.contains("/src/") || !path.contains("/")) && !path.matches("(?:.*?/)?(?:target|build|\\.git|node_modules|vendor)/.*")
            && !path.matches("(?:.*?/)?src/(?:test|it|testFixtures)/.*");
    }
    public void add(SymbolIndex index,String source,String path) throws IOException {
        ParseResult<CompilationUnit> result=parser.parse(source);
        if(!result.isSuccessful() || result.getResult().isEmpty()) throw new IOException("Cannot parse Java source "+path+": "+result.getProblems());
        CompilationUnit cu=result.getResult().get();
        String pkg=cu.getPackageDeclaration().map(p->p.getNameAsString()+".").orElse("");
        for(TypeDeclaration<?> t:cu.getTypes()) addType(index,t,pkg,true);
    }
    private void addType(SymbolIndex index, TypeDeclaration<?> declaration,String prefix,boolean outerAccessible) {
        boolean implicit=declaration.getParentNode().filter(n->n instanceof ClassOrInterfaceDeclaration c && c.isInterface()).isPresent();
        boolean accessible=outerAccessible && (declaration.isPublic()||declaration.isProtected()||implicit);
        if(!accessible)return;
        SymbolIndex.Type type=new SymbolIndex.Type(prefix+declaration.getNameAsString());
        type.forRemoval=removal(declaration);
        type.generated=declaration.getAnnotations().stream().anyMatch(a->Set.of("Data","Getter","Setter","Builder","Value").contains(a.getName().getIdentifier()));
        if(declaration instanceof ClassOrInterfaceDeclaration c) {
            c.getExtendedTypes().forEach(p->type.parents.add(p.getNameWithScope()));
            c.getImplementedTypes().forEach(p->type.parents.add(p.getNameWithScope()));
        }
        boolean isInterface=declaration instanceof ClassOrInterfaceDeclaration c && c.isInterface();
        for(BodyDeclaration<?> body:declaration.getMembers()) {
            if(body instanceof MethodDeclaration m && !m.isPrivate() && (isInterface||m.isPublic()||m.isProtected()))
                type.members.add(new SymbolIndex.Member(m.getNameAsString(),m.getParameters().stream().map(p->p.getTypeAsString()).toList(),true,m.getParameters().stream().anyMatch(Parameter::isVarArgs),removal(m)));
            if(body instanceof ConstructorDeclaration c && (c.isPublic()||c.isProtected()))
                type.members.add(new SymbolIndex.Member(declaration.getNameAsString(),c.getParameters().stream().map(p->p.getTypeAsString()).toList(),true,c.getParameters().stream().anyMatch(Parameter::isVarArgs),false));
            if(body instanceof FieldDeclaration f && !f.isPrivate() && (isInterface||f.isPublic()||f.isProtected()))
                f.getVariables().forEach(v->type.members.add(new SymbolIndex.Member(v.getNameAsString(),List.of(),false,false,removal(f))));
            if(body instanceof TypeDeclaration<?> nested) {
                addType(index,nested,type.name+".",accessible);
                if(index.types.containsKey(type.name+"."+nested.getNameAsString()))
                    type.members.add(new SymbolIndex.Member(nested.getNameAsString(),List.of(),false,false,false));
            }
        }
        if(declaration instanceof RecordDeclaration r) r.getParameters().forEach(p->type.members.add(new SymbolIndex.Member(p.getNameAsString(),List.of(),true,false,false)));
        if(declaration instanceof EnumDeclaration e) {
            e.getEntries().forEach(v->type.members.add(new SymbolIndex.Member(v.getNameAsString(),List.of(),false,false,false)));
            type.members.add(new SymbolIndex.Member("values",List.of(),true,false,false));
            type.members.add(new SymbolIndex.Member("valueOf",List.of("String"),true,false,false));
        }
        SymbolIndex.Type variant=index.types.putIfAbsent(type.name,type);
        if(variant!=null) {
            // Multi-release, Android/JRE and starter variants share qualified names.
            // Accept their public API union until compiled output supplies a precise variant.
            for(SymbolIndex.Member member:type.members)if(!variant.members.contains(member))variant.members.add(member);
            for(String parent:type.parents)if(!variant.parents.contains(parent))variant.parents.add(parent);
            variant.generated|=type.generated;variant.forRemoval&=type.forRemoval;
        }
    }
    private boolean removal(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().anyMatch(a->a.getName().getIdentifier().equals("Deprecated") && a instanceof NormalAnnotationExpr n
            && n.getPairs().stream().anyMatch(p->p.getNameAsString().equals("forRemoval") && p.getValue().toString().equals("true")));
    }
}
