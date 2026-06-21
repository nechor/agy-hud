package javax.lang.model;

import java.util.Set;

public class SourceVersion {
    private static final Set<String> KEYWORDS = Set.of(
        "abstract", "continue", "for", "new", "switch", "assert", "default", "goto", "package", "synchronized",
        "boolean", "do", "if", "private", "this", "break", "double", "implements", "protected", "throw",
        "byte", "else", "import", "public", "throws", "case", "enum", "instanceof", "return", "transient",
        "catch", "extends", "int", "short", "try", "char", "final", "interface", "static", "void",
        "class", "finally", "long", "strictfp", "volatile", "const", "float", "native", "super", "while",
        "true", "false", "null"
    );

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        String[] parts = name.toString().split("\\.");
        for (String part : parts) {
            if (!isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isKeyword(CharSequence name) {
        if (name == null) {
            return false;
        }
        return KEYWORDS.contains(name.toString());
    }
}
