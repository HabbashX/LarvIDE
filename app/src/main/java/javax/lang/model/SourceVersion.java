package javax.lang.model;

public enum SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11;

    public static SourceVersion latest() {
        return RELEASE_11;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_11;
    }

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) return false;
        String s = name.toString();
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        if (s == null) return false;
        String kw = s.toString();
        return kw.equals("abstract") || kw.equals("assert") || kw.equals("boolean")
            || kw.equals("break") || kw.equals("byte") || kw.equals("case")
            || kw.equals("catch") || kw.equals("char") || kw.equals("class")
            || kw.equals("const") || kw.equals("continue") || kw.equals("default")
            || kw.equals("do") || kw.equals("double") || kw.equals("else")
            || kw.equals("enum") || kw.equals("extends") || kw.equals("final")
            || kw.equals("finally") || kw.equals("float") || kw.equals("for")
            || kw.equals("goto") || kw.equals("if") || kw.equals("implements")
            || kw.equals("import") || kw.equals("instanceof") || kw.equals("int")
            || kw.equals("interface") || kw.equals("long") || kw.equals("native")
            || kw.equals("new") || kw.equals("package") || kw.equals("private")
            || kw.equals("protected") || kw.equals("public") || kw.equals("return")
            || kw.equals("short") || kw.equals("static") || kw.equals("strictfp")
            || kw.equals("super") || kw.equals("switch") || kw.equals("synchronized")
            || kw.equals("this") || kw.equals("throw") || kw.equals("throws")
            || kw.equals("transient") || kw.equals("try") || kw.equals("void")
            || kw.equals("volatile") || kw.equals("while");
    }

    public static boolean isName(CharSequence name) {
        if (name == null || name.length() == 0) return false;
        String s = name.toString();
        for (String part : s.split("\\.", -1)) {
            if (!isIdentifier(part)) return false;
        }
        return true;
    }

    public boolean isSupported() {
        return ordinal() <= latestSupported().ordinal();
    }

    public String toString() {
        switch (this) {
            case RELEASE_0: return "0";
            case RELEASE_1: return "1.1";
            case RELEASE_2: return "1.2";
            case RELEASE_3: return "1.3";
            case RELEASE_4: return "1.4";
            case RELEASE_5: return "5";
            case RELEASE_6: return "6";
            case RELEASE_7: return "7";
            case RELEASE_8: return "8";
            case RELEASE_9: return "9";
            case RELEASE_10: return "10";
            case RELEASE_11: return "11";
        }
        return "";
    }
}