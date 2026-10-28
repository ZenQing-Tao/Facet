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

    /** 伪装 SIM / 网络国家码（两位小写 ISO，如 us）：c_<包名>，只对该应用自己的进程生效 */
    static final String COUNTRY = "c_";

    /** 伪装厂商（Build.MANUFACTURER / BRAND，如 samsung）：m_<包名>，只对该应用自己的进程生效 */
    static final String MAKER = "m_";

    private Const() {}
}
