package app.tongxing.store;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("member")
public class Member {
    public String id;
    public String username;
    public String displayName;
    public String passwordHash;
    public String pairId;
    public boolean sharing;
    public String shareKey;
    public boolean usageEnabled;
    public Integer screenMinutes;
    public String screenDate;
}
