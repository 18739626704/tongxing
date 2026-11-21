package app.tongxing.store;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("position_sample")
public class PositionSample {
    public String id;
    public String pairId;
    public String memberId;
    public double latitude;
    public double longitude;
    public double accuracy;
    public int battery;
    public boolean charging;
    public long recordedAt;
}
