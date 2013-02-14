package com.psddev.dari.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Css {

    private final char[] css;
    private final int cssLength;
    private int cssIndex;

    private final Map<String, List<CssDeclaration>> rules = new LinkedHashMap<String, List<CssDeclaration>>();

    public Css(String css) throws IOException {
        this.css = css.toCharArray();
        this.cssLength = css.length();

        while (readRule(null)) {
        }
    }

    public String getValue(String selector, String property) {
        List<CssDeclaration> declarations = rules.get(selector);

        if (declarations != null) {
            for (int i = declarations.size() - 1; i >= 0; -- i) {
                CssDeclaration declaration = declarations.get(i);

                if (declaration.getProperty().equals(property)) {
                    return declaration.getValue();
                }
            }
        }

        return null;
    }

    private void readComment() throws IOException {
        for (; cssIndex < cssLength; ++ cssIndex) {
            if (!Character.isWhitespace(css[cssIndex])) {
                break;
            }
        }

        boolean started = false;
        boolean inSingle = false;
        boolean inMulti = false;
        boolean multiEnding = false;

        for (; cssIndex < cssLength; ++ cssIndex) {
            char letter = css[cssIndex];

            if (letter == '/') {
                if (started) {
                    inSingle = true;

                } else if (multiEnding) {
                    break;

                } else {
                    started = true;
                    multiEnding = false;
                }

            } else if (started && letter == '*') {
                if (inMulti) {
                    multiEnding = true;

                } else {
                    inMulti = true;
                }

            } else if (inSingle && (letter == '\r' || letter == '\n')) {
                break;

            } else if (!(inSingle || inMulti)) {
                if (started) {
                    -- cssIndex;
                }
                break;
            }
        }
    }

    private boolean readRule(Set<String> parents) throws IOException {
        readComment();

        if (cssIndex < cssLength) {
            if (css[cssIndex] != '@') {
                return readSelector(parents);
            }

            StringBuilder atRule = new StringBuilder();
            atRule.append('@');

            for (++ cssIndex; cssIndex < cssLength; ++ cssIndex) {
                char letter = css[cssIndex];

                if (letter == '{') {
                    String atRuleString = atRule.toString().trim();
                    Set<String> atRuleParents = new LinkedHashSet<String>();

                    if (parents == null) {
                        atRuleParents.add(atRuleString);

                    } else {
                        for (String parent : parents) {
                            atRuleParents.add(atRuleString + " " + parent);
                        }
                    }

                    ++ cssIndex;
                    readDeclarations(atRuleParents);
                    return true;

                } else if (letter == ';') {
                    ++ cssIndex;
                    return true;

                } else {
                    atRule.append(letter);
                }
            }
        }

        return false;
    }

    private boolean readSelector(Set<String> parents) throws IOException {
        readComment();

        Set<String> selectors = null;
        StringBuilder newSelector = new StringBuilder();

        for (; cssIndex < cssLength; ++ cssIndex) {
            char letter = css[cssIndex];
            boolean brace = letter == '{';

            if (brace || letter == ',') {
                if (selectors == null) {
                    selectors = new LinkedHashSet<String>();
                }

                String newSelectorString = newSelector.toString().trim();

                if (parents == null) {
                    selectors.add(newSelectorString);

                } else {
                    for (String parent : parents) {
                        selectors.add(parent + " " + newSelectorString);
                    }
                }

                newSelector.setLength(0);

                if (brace) {
                    ++ cssIndex;
                    break;

                } else {
                    readComment();
                }

            } else {
                newSelector.append(letter);
            }
        }

        if (selectors == null) {
            return false;
        }

        List<CssDeclaration> declarations = readDeclarations(selectors);

        for (String selector : selectors) {
            List<CssDeclaration> selectorDeclarations = rules.get(selector);

            if (selectorDeclarations == null) {
                rules.put(selector, new ArrayList<CssDeclaration>(declarations));

            } else {
                selectorDeclarations.addAll(declarations);
            }
        }

        return true;
    }

    private List<CssDeclaration> readDeclarations(Set<String> selectors) throws IOException {
        readComment();

        List<CssDeclaration> declarations = new ArrayList<CssDeclaration>();
        StringBuilder property = new StringBuilder();
        StringBuilder value = new StringBuilder();
        StringBuilder current = property;

        for (int lastDeclaration = cssIndex; cssIndex < cssLength; ++ cssIndex) {
            char letter = css[cssIndex];

            if (letter == '{') {
                cssIndex = lastDeclaration;
                property.setLength(0);
                readRule(selectors);
                lastDeclaration = cssIndex + 1;

            } else if (letter == ':') {
                current = value;
                readComment();

            } else if (letter == ';') {
                declarations.add(new CssDeclaration(property.toString().trim(), value.toString().trim()));
                property.setLength(0);
                value.setLength(0);
                current = property;
                lastDeclaration = cssIndex + 1;
                readComment();

            } else if (letter == '}') {
                ++ cssIndex;
                break;

            } else {
                current.append(letter);
            }
        }

        return declarations;
    }
}
