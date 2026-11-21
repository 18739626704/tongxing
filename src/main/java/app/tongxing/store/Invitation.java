package app.tongxing.store;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("invitation")
public class Invitation {
    public String id;
    public String memberId;
    public long expiresAt;
}
