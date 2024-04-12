package org.fordes.adfs.constant;

import java.io.File;

public class Constants {

    public static final String ROOT_PATH = System.getProperty("user.dir");
    public static final String FILE_SEPARATOR = File.separator;
    public static final String LOCAL_RULE_SUFFIX = ROOT_PATH + File.separator + "rule";
    public static final String EMPTY = "";
    public static final String DOT = ".";
    public static final String HEADER_DATE = "${date}";
    public static final String HEADER_NAME = "${name}";
    public static final String HEADER_TOTAL = "${total}";
    public static final char ASTERISK = '*';
    public static final char QUESTION_MARK = '?';
    public static final char A = 'a';
    public static final String LOCALHOST_V6 = "::1";
    public static final String LOCALHOST = "localhost";

}
