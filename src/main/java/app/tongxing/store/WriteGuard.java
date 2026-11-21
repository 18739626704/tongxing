package app.tongxing.store;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WriteGuard {
    // This private, single-instance deployment serializes consent changes with location reads and writes.
    @Select("SELECT id FROM write_guard WHERE id = 1 FOR UPDATE")
    int lock();
}
