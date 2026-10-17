package app.facet.kit;

final class Const {
    static final String SELF = "app.facet.kit";
    static final String PREFS = "config";
    /** 自定义名称：n_<包名> */
    static final String NAME = "n_";
    /** 自定义图标（PNG base64）：i_<包名> */
    static final String ICON = "i_";
    /** 是否套用系统图标形状：s_<包名>，默认 true */
    static final String SHAPE = "s_";

    /** 通知是否折叠进「更多通知」：f_<包名>，默认 false */
    static final String FOLD = "f_";

    private Const() {}
}
