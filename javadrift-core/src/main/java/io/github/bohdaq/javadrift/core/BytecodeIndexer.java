package io.github.bohdaq.javadrift.core;
import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
public final class BytecodeIndexer {
    public void add(SymbolIndex index,Path entry,boolean project) throws IOException {
        if(!Files.exists(entry))return;
        if(Files.isDirectory(entry)) {
            try(var files=Files.walk(entry)) {
                for(Path file:files.filter(p->p.toString().endsWith(".class")).sorted().toList())read(index,Files.readAllBytes(file),project);
            }
        } else if(entry.toString().endsWith(".jar")) {
            try(JarFile jar=new JarFile(entry.toFile())) {
                for(JarEntry file:jar.stream().filter(e->e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")).sorted(Comparator.comparing(JarEntry::getName)).toList())
                    try(InputStream in=jar.getInputStream(file)) {read(index,in.readAllBytes(),project);}
            }
        } else throw new IOException("Classpath entry must be a directory or jar: "+entry);
    }
    public void addJdkParents(SymbolIndex index) throws IOException {
        Set<String> tried=new HashSet<>();boolean changed;
        do {
            changed=false;
            List<String> parents=index.types.values().stream().flatMap(t->t.parents.stream()).distinct().toList();
            for(String parent:parents) {
                if(index.types.containsKey(parent) || !parent.startsWith("java.") || !tried.add(parent))continue;
                try(InputStream in=ClassLoader.getSystemResourceAsStream(parent.replace('.','/')+".class")) {
                    if(in!=null) {read(index,in.readAllBytes(),false);changed=true;}
                }
            }
        } while(changed);
    }
    private void read(SymbolIndex index,byte[] bytes,boolean project) throws IOException {
        try {
            ClassReader reader=new ClassReader(bytes);
            SymbolIndex.Type type=new SymbolIndex.Type(reader.getClassName().replace('/','.').replace('$','.'));
            type.compiled=true;type.project=project;
            boolean[] visible={(reader.getAccess()&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED))!=0};
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                public void visit(int version,int access,String name,String signature,String parent,String[] interfaces) {
                    if(parent!=null)type.parents.add(parent.replace('/','.').replace('$','.'));
                    for(String p:interfaces)type.parents.add(p.replace('/','.').replace('$','.'));
                }
                public void visitInnerClass(String name,String outer,String inner,int access) {
                    if(name.equals(reader.getClassName()))visible[0]=(access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED))!=0;
                    if(outer!=null && outer.equals(reader.getClassName()) && inner!=null && (access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED))!=0)
                        type.members.add(new SymbolIndex.Member(inner,List.of(),false,false,false));
                }
                public AnnotationVisitor visitAnnotation(String descriptor,boolean visible) {return deprecated(descriptor,v->type.forRemoval=v);}
                public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value) {
                    if(!accessible(access))return null;
                    int slot=type.members.size();type.members.add(new SymbolIndex.Member(name,List.of(),false,false,false));
                    return new FieldVisitor(Opcodes.ASM9) {public AnnotationVisitor visitAnnotation(String d,boolean v) {return deprecated(d,r->replace(slot,r));}};
                }
                public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(!accessible(access) || name.equals("<clinit>"))return null;
                    String member=name.equals("<init>")?type.simpleName():name;
                    List<String> params=Arrays.stream(org.objectweb.asm.Type.getArgumentTypes(descriptor)).map(org.objectweb.asm.Type::getClassName).toList();
                    int slot=type.members.size();type.members.add(new SymbolIndex.Member(member,params,true,(access&Opcodes.ACC_VARARGS)!=0,false));
                    return new MethodVisitor(Opcodes.ASM9) {public AnnotationVisitor visitAnnotation(String d,boolean v) {return deprecated(d,r->replace(slot,r));}};
                }
                private void replace(int slot,boolean removal) {
                    var m=type.members.get(slot);type.members.set(slot,new SymbolIndex.Member(m.name(),m.parameters(),m.method(),m.varargs(),removal));
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            if(visible[0]) {
                if(project)index.types.put(type.name,type);else index.types.putIfAbsent(type.name,type);
            }
        } catch(IllegalArgumentException e) {throw new IOException("Unsupported or malformed class file",e);}
    }
    private boolean accessible(int access) {return (access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED))!=0 && (access&Opcodes.ACC_SYNTHETIC)==0;}
    private AnnotationVisitor deprecated(String descriptor,java.util.function.Consumer<Boolean> set) {
        if(!descriptor.equals("Ljava/lang/Deprecated;"))return null;
        return new AnnotationVisitor(Opcodes.ASM9) {public void visit(String name,Object value) {if(name.equals("forRemoval"))set.accept(Boolean.TRUE.equals(value));}};
    }
}
