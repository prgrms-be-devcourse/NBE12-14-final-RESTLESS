package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** SOURCE 보존 Lombok 어노테이션은 architecture/overview.md 2절에 따라 Java 구문 트리로 검사한다. */
class FanMeetingSourceRulesTest {
    @Test
    void dataAnnotationIsRejected() {
        assertThat(violations("Example.java", "import lombok.Data; @Data class Example {}"))
                .anyMatch(message -> message.contains("lombok.Data"));
    }

    @Test
    void publicDefaultConstructorIsRejected() {
        assertThat(violations("Example.java", "import lombok.NoArgsConstructor; @NoArgsConstructor class Example {}"))
                .anyMatch(message -> message.contains("PROTECTED"));
    }

    private static final Set<String> ALLOWED = Set.of(
            "lombok.Getter", "lombok.NoArgsConstructor", "lombok.RequiredArgsConstructor");

    @Test
    void productionUsesAllowedLombok() throws IOException {
        Path root = Path.of("src/main/java/com/restless/fanmeeting");
        assertThat(root).isDirectory();
        List<String> errors = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            List<Path> sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            assertThat(sources).isNotEmpty();
            for (Path source : sources) {
                errors.addAll(violations(source.toString(), Files.readString(source)));
            }
        }
        assertThat(errors).as("팬미팅 Lombok 규칙 — Getter, PROTECTED NoArgsConstructor, RequiredArgsConstructor만 허용")
                .isEmpty();
    }

    @Test
    void qualifiedAnnotationsAndNonProtectedAccessAreRejected() {
        assertThat(violations("Example.java", "@lombok.Setter class Example {}"))
                .anyMatch(message -> message.contains("lombok.Setter"));
        assertThat(violations("Example.java",
                "@lombok.NoArgsConstructor(access=lombok.AccessLevel.PUBLIC) class Example {}"))
                .anyMatch(message -> message.contains("PROTECTED"));
    }

    @Test
    void commentsStringsAndNonLombokAnnotationsAreIgnored() {
        String source = """
                // @lombok.Data
                @interface Data {}
                @Data class Example { String text = "@lombok.Setter"; }
                """;
        assertThat(violations("Example.java", source)).isEmpty();
    }

    @Test
    void allowedAnnotationsAndStaticProtectedImportPass() {
        String source = """
                import lombok.Getter;
                import lombok.NoArgsConstructor;
                import lombok.RequiredArgsConstructor;
                import static lombok.AccessLevel.PROTECTED;
                @Getter @NoArgsConstructor(access=PROTECTED)
                class Example {}
                @RequiredArgsConstructor class Another {}
                """;
        assertThat(violations("Example.java", source)).isEmpty();
        assertThat(violations("Example.java", """
                @lombok.NoArgsConstructor(access=lombok.AccessLevel.PROTECTED) class Example {}
                """)).isEmpty();
        assertThat(violations("Example.java", """
                import lombok.NoArgsConstructor;
                import static lombok.AccessLevel.*;
                @NoArgsConstructor(access=PROTECTED) class Example {}
                """)).isEmpty();
    }

    @Test
    void staticLombokAnnotationCannotBypassAllowList() {
        assertThat(violations("Example.java",
                "import static lombok.Builder.Default; class Example { @Default int value; }"))
                .anyMatch(message -> message.contains("lombok.Builder.Default"));
    }

    @Test
    void unresolvableLombokWildcardIsReported() {
        assertThat(violations("Example.java", "import lombok.*; @Data class Example {}"))
                .anyMatch(message -> message.contains("와일드카드"));
        assertThat(violations("Example.java",
                "import static lombok.Builder.*; class Example { @Default int value; }"))
                .anyMatch(message -> message.contains("와일드카드"));
    }

    private static List<String> violations(String file, String source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Java 구문 검사에는 JDK 21이 필요합니다").isNotNull();
        JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///Example.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none"), null, List.of(input));
            CompilationUnitTree unit = task.parse().iterator().next();
            assertThat(diagnostics.getDiagnostics()).as(file + " Java 구문 오류")
                    .noneMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR);
            Map<String, String> imports = new HashMap<>();
            Set<String> staticImports = new HashSet<>();
            List<String> errors = new ArrayList<>();
            unit.getImports().forEach(tree -> {
                String name = tree.getQualifiedIdentifier().toString();
                if (tree.isStatic()) {
                    staticImports.add(name);
                }
                if (!name.endsWith(".*")) {
                    imports.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
                if (name.startsWith("lombok.") && name.endsWith(".*")
                        && !(tree.isStatic() && name.equals("lombok.AccessLevel.*"))) {
                    errors.add(file + ": Lombok 와일드카드는 타입을 확정할 수 없습니다. 명시적 import로 검사하세요.");
                }
            });
            Trees trees = Trees.instance(task);
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitAnnotation(AnnotationTree annotation, Void unused) {
                    String name = annotation.getAnnotationType().toString();
                    String qualified = imports.getOrDefault(name, name);
                    long line = unit.getLineMap().getLineNumber(
                            trees.getSourcePositions().getStartPosition(unit, annotation));
                    String location = file + ":" + line + " @" + qualified;
                    if (qualified.startsWith("lombok.") && !ALLOWED.contains(qualified)) {
                        errors.add(location + " — Getter, PROTECTED NoArgsConstructor, RequiredArgsConstructor만 사용하세요.");
                    }
                    if (qualified.equals("lombok.NoArgsConstructor")
                            && !hasProtectedAccess(annotation, imports, staticImports)) {
                        errors.add(location + " — access = AccessLevel.PROTECTED를 지정하세요.");
                    }
                    return super.visitAnnotation(annotation, unused);
                }
            }.scan(unit, null);
            return errors;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static boolean hasProtectedAccess(AnnotationTree annotation, Map<String, String> imports,
                                               Set<String> staticImports) {
        for (var argument : annotation.getArguments()) {
            if (argument instanceof AssignmentTree assignment && assignment.getVariable().toString().equals("access")) {
                String value = assignment.getExpression().toString();
                if (value.equals("PROTECTED")) {
                    return staticImports.contains("lombok.AccessLevel.PROTECTED")
                            || staticImports.contains("lombok.AccessLevel.*");
                }
                int dot = value.indexOf('.');
                if (dot >= 0) {
                    value = imports.getOrDefault(value.substring(0, dot), value.substring(0, dot)) + value.substring(dot);
                }
                return value.equals("lombok.AccessLevel.PROTECTED");
            }
        }
        return false;
    }
}
