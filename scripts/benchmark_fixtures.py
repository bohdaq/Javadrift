"""Deterministic source-only inputs shared by benchmark candidates and references."""
import hashlib


def generate(root, profile, classes=5000, documents=500):
    if profile not in ('java', 'mixed') or classes < 2 or documents < 1:
        raise ValueError('Fixtures need java/mixed, at least two classes and one document')
    digest = hashlib.sha256()

    def write(relative, text):
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8', newline='\n')
        digest.update(relative.encode('utf-8') + b'\0' + text.encode('utf-8') + b'\0')

    java_count = classes if profile == 'java' else classes // 2
    kotlin_count = classes - java_count
    for i in range(java_count):
        write(f'src/main/java/benchmark/Type{i}.java',
              f'package benchmark; public class Type{i} {{ public void place() {{}} }}\n')
    for i in range(kotlin_count):
        write(f'src/main/kotlin/benchmark/KotlinType{i}.kt',
              f'package benchmark\nclass KotlinType{i} {{\n'
              '    val language: String = "en"\n'
              '    fun place(name: String = "world"): String = name\n'
              f'    fun consume(value: Type{i % java_count}): String = value.toString()\n'
              '}\n')
    for i in range(documents):
        text = f'`benchmark.Type{i % java_count}` `Type{i % java_count}#place`\n'
        if kotlin_count:
            text += (f'`benchmark.KotlinType{i % kotlin_count}` '
                     f'`KotlinType{i % kotlin_count}#place` `KotlinType{i % kotlin_count}#language`\n')
        extension = 'adoc' if profile == 'mixed' and i % 2 else 'md'
        write(f'docs/guide-{i}.{extension}', text)
    return {'profile': profile, 'classes': classes, 'documents': documents,
            'javaFiles': java_count, 'kotlinFiles': kotlin_count,
            'markdownFiles': documents - (documents // 2 if profile == 'mixed' else 0),
            'asciidocFiles': documents // 2 if profile == 'mixed' else 0,
            'fixtureSha256': digest.hexdigest()}
