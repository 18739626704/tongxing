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
    public boolean appUsageEnabled;
    public boolean deviceStatusEnabled;
    public String deviceModel;
    public String networkType;
    public Long deviceStatusAt;
    public Long deviceUnlockedAt;
    public String deviceMotion;
    public String candidateMotion;
    public Integer screenMinutes;
    public String screenDate;
    public boolean journeyEnabled;
    public String journeyState;
    public boolean liveScreenEnabled;
    public String liveScreenState;
    public Long liveScreenAt;
}
