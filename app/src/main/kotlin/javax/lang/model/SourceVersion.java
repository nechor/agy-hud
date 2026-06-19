package javax.lang.model;

public class SourceVersion {
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
}
