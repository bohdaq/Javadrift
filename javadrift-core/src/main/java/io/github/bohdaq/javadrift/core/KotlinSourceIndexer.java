package io.github.bohdaq.javadrift.core;

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Parses declarations only; no compiler analysis, processors or project code run. */
final class KotlinSourceIndexer implements AutoCloseable {
    private final Disposable disposable=Disposer.newDisposable("javadrift-kotlin");
    private final KtPsiFactory factory;
    private record Extension(String receiver,SymbolIndex.Type members) {}
    private final List<Extension> extensions=new ArrayList<>();
    void finish(SymbolIndex index) {
        for(var extension:extensions)index.resolve(extension.receiver()).ifPresent(type->{
            for(var member:extension.members().members)if(!type.members.contains(member))type.members.add(member);
        });
    }
    KotlinSourceIndexer() {
        var environment=KotlinCoreEnvironment.createForProduction(disposable,new CompilerConfiguration(),EnvironmentConfigFiles.JVM_CONFIG_FILES);
        factory=new KtPsiFactory(environment.getProject(),false);
    }
    public void close() {Disposer.dispose(disposable);}
    void add(SymbolIndex index,String source,String path) throws IOException {
        KtFile file=factory.createFile(Path.of(path.substring(path.lastIndexOf(':')+1)).getFileName().toString(),source);
        var errors=PsiTreeUtil.findChildrenOfType(file,PsiErrorElement.class);
        if(!errors.isEmpty())throw new IOException("Cannot parse Kotlin source "+path+": "+errors.iterator().next().getErrorDescription());
        String pkg=file.getPackageFqName().asString();String prefix=pkg.isEmpty()?"":pkg+".";
        documentationNames(index,file.getDeclarations(),prefix);
        for(KtDeclaration declaration:file.getDeclarations())if(declaration instanceof KtClassOrObject type)addType(index,type,prefix);
        for(KtDeclaration declaration:file.getDeclarations())if(declaration instanceof KtTypeAlias alias && visible(alias)) {
            var type=new SymbolIndex.Type(prefix+alias.getName());
            if(alias.getTypeReference()!=null)type.parents.add(alias.getTypeReference().getText().replaceAll("<.*>",""));
            merge(index,type);
        }
        String base=file.getName().replaceFirst("\\.kt$","");
        String facade=Character.toUpperCase(base.charAt(0))+base.substring(1)+"Kt";
        for(var annotation:file.getAnnotationEntries())if(annotation.getShortName()!=null && annotation.getShortName().asString().equals("JvmName") && !annotation.getValueArguments().isEmpty())
            facade=annotation.getValueArguments().get(0).getArgumentExpression().getText().replace("\"","");
        var top=new SymbolIndex.Type(prefix+facade);
        for(KtDeclaration declaration:file.getDeclarations()) {
            addMember(top,declaration);
            if(declaration instanceof KtNamedFunction function && visible(function) && function.getReceiverTypeReference()!=null) {
                var members=new SymbolIndex.Type("extensions");addMember(members,function);
                extensions.add(new Extension(function.getReceiverTypeReference().getText().replaceAll("<.*>","").replaceFirst("\\?$",""),members));
                for(var member:members.members) {
                    var parameters=new ArrayList<>(member.parameters());parameters.add(0,parameterType(function.getReceiverTypeReference()));
                    top.members.add(new SymbolIndex.Member(member.name(),parameters,true,member.varargs(),false));
                }
            }
        }
        if(!top.members.isEmpty())merge(index,top);
    }
    void addDocumentationTypes(SymbolIndex index,String source,String path) {
        var file=factory.createFile(Path.of(path).getFileName().toString(),source);
        if(!PsiTreeUtil.findChildrenOfType(file,PsiErrorElement.class).isEmpty())return;
        String pkg=file.getPackageFqName().asString();
        documentationNames(index,file.getDeclarations(),pkg.isEmpty()?"":pkg+".");
    }
    private void documentationNames(SymbolIndex index,List<KtDeclaration> declarations,String prefix) {
        for(var declaration:declarations)if(declaration instanceof KtClassOrObject type && type.getName()!=null) {
            String name=prefix+type.getName();index.documentationTypes.add(name);
            documentationNames(index,type.getDeclarations(),name+".");
        }
    }
    private boolean visible(KtModifierListOwner declaration) {
        return !declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) && !declaration.hasModifier(KtTokens.INTERNAL_KEYWORD);
    }
    private void addType(SymbolIndex index,KtClassOrObject declaration,String prefix) {
        if(!visible(declaration) || declaration.getName()==null)return;
        var type=new SymbolIndex.Type(prefix+declaration.getName());
        for(var parent:declaration.getSuperTypeListEntries())if(parent.getTypeReference()!=null)
            type.parents.add(parent.getTypeReference().getText().replaceAll("<.*>",""));
        if(declaration instanceof KtClass klass) {
            var constructor=klass.getPrimaryConstructor();
            if(constructor==null || visible(constructor))addFunction(type,klass.getName(),klass.getPrimaryConstructorParameters());
            int component=0;
            for(var parameter:klass.getPrimaryConstructorParameters())if(parameter.hasValOrVar()) {
                if(visible(parameter))addProperty(type,parameter.getName(),parameter.isMutable(),parameter.getTypeReference());
                if(klass.isData())type.members.add(new SymbolIndex.Member("component"+(++component),List.of(),true,false,false));
            }
            if(klass.isData()) {
                type.members.add(new SymbolIndex.Member("copy",List.of("Object[]"),true,true,false));
            }
            if(klass.isEnum()) {
                type.members.add(new SymbolIndex.Member("values",List.of(),true,false,false));
                type.members.add(new SymbolIndex.Member("valueOf",List.of("String"),true,false,false));
                type.members.add(new SymbolIndex.Member("entries",List.of(),false,false,false));
            }
        }
        for(var member:declaration.getDeclarations()) {
            if(member instanceof KtEnumEntry entry)type.members.add(new SymbolIndex.Member(entry.getName(),List.of(),false,false,false));
            else if(member instanceof KtClassOrObject nested) {
                addType(index,nested,type.name+".");
                var child=index.types.get(type.name+"."+nested.getName());
                if(child!=null) {
                    type.members.add(new SymbolIndex.Member(nested.getName(),List.of(),false,false,false));
                    // Kotlin companion members are also callable through the containing class.
                    if(nested instanceof KtObjectDeclaration object && object.isCompanion())type.members.addAll(child.members);
                }
            } else addMember(type,member);
        }
        merge(index,type);
    }
    private void addMember(SymbolIndex.Type type,KtDeclaration declaration) {
        if(!visible(declaration))return;
        if(declaration instanceof KtNamedFunction function && function.getName()!=null) {
            addFunction(type,function.getName(),function.getValueParameters());
            // Keep both Kotlin source spelling and explicit JVM spelling.
            for(var annotation:function.getAnnotationEntries())if(annotation.getShortName()!=null && annotation.getShortName().asString().equals("JvmName") && !annotation.getValueArguments().isEmpty())
                addFunction(type,annotation.getValueArguments().get(0).getArgumentExpression().getText().replace("\"",""),function.getValueParameters());
        }
        if(declaration instanceof KtProperty property && property.getName()!=null)addProperty(type,property.getName(),property.isVar(),property.getTypeReference());
        if(declaration instanceof KtSecondaryConstructor constructor)addFunction(type,type.simpleName(),constructor.getValueParameters());
    }
    private void addProperty(SymbolIndex.Type type,String name,boolean mutable,KtTypeReference reference) {
        type.members.add(new SymbolIndex.Member(name,List.of(),false,false,false));
        String capital=Character.toUpperCase(name.charAt(0))+name.substring(1);
        String getter=name.startsWith("is") && name.length()>2 && Character.isUpperCase(name.charAt(2))?name:"get"+capital;
        type.members.add(new SymbolIndex.Member(getter,List.of(),true,false,false));
        if(mutable)type.members.add(new SymbolIndex.Member("set"+(getter.equals(name)?name.substring(2):capital),List.of(parameterType(reference)),true,false,false));
    }
    private String parameterType(KtTypeReference type) {
        if(type==null)return "Object";
        String text=type.getText();
        if(text.contains("->"))return "Object";
        boolean nullable=text.endsWith("?");text=text.replaceFirst("\\?$","");
        if(nullable)return switch(text){case "Int"->"Integer";case "Char"->"Character";default->text;};
        return switch(text){case "Int"->"int";case "Long"->"long";case "Short"->"short";case "Byte"->"byte";case "Char"->"char";case "Boolean"->"boolean";case "Float"->"float";case "Double"->"double";case "Any"->"Object";default->text;};
    }
    private void addFunction(SymbolIndex.Type type,String name,List<KtParameter> parameters) {
        if(parameters.stream().limit(Math.max(0,parameters.size()-1)).anyMatch(p->p.hasModifier(KtTokens.VARARG_KEYWORD))) {
            type.members.add(new SymbolIndex.Member(name,List.of("Object[]"),true,true,false));return;
        }
        List<List<String>> signatures=new ArrayList<>();signatures.add(new ArrayList<>());
        for(var parameter:parameters) {
            String text=parameterType(parameter.getTypeReference());
            boolean vararg=parameter.hasModifier(KtTokens.VARARG_KEYWORD);
            List<List<String>> next=new ArrayList<>();
            for(var signature:signatures) {
                var included=new ArrayList<>(signature);included.add(text+(vararg?"[]":""));next.add(included);
                if(parameter.hasDefaultValue() && next.size()<256)next.add(new ArrayList<>(signature));
            }
            signatures=next;
        }
        // Non-final Kotlin varargs cannot be represented by the Java arity model.
        boolean varargs=!parameters.isEmpty() && parameters.get(parameters.size()-1).hasModifier(KtTokens.VARARG_KEYWORD);
        for(var signature:signatures)type.members.add(new SymbolIndex.Member(name,List.copyOf(signature),true,varargs,false));
    }
    private void merge(SymbolIndex index,SymbolIndex.Type type) {
        type.kotlinSource=true;
        var existing=index.types.putIfAbsent(type.name,type);
        if(existing!=null) {
            existing.kotlinSource=true;
            for(var member:type.members)if(!existing.members.contains(member))existing.members.add(member);
            for(var parent:type.parents)if(!existing.parents.contains(parent))existing.parents.add(parent);
        }
    }
}
