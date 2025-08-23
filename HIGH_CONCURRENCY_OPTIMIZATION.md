# 高并发优化说明

## 问题描述
在高并发（2000+并发）场景下，出现以下错误：
```
Failed to acquire channel from pool: 192.168.1.111:8089
Address already in use: no further information: /192.168.1.111:8089
```

## 优化内容

### 1. 连接池配置优化
- **最大连接数**：从64增加到500
- **等待队列大小**：从10000增加到20000
- **EventLoopGroup线程数**：根据CPU核心数动态调整（CPU核心数 * 2，最小16）

### 2. 连接管理优化
- **连接释放机制**：改进连接释放逻辑，避免连接泄漏
- **连接健康检查**：在获取连接时检查连接状态
- **连接清理**：在连接释放时清理相关属性

### 3. 网络参数优化
- **SO_REUSEADDR**：允许地址重用
- **SO_LINGER**：设置为0，快速关闭连接
- **SO_RCVBUF/SO_SNDBUF**：增加缓冲区大小到64KB

### 4. 监控和调试
- **连接池状态监控**：添加`logPoolStatus()`方法
- **详细关闭日志**：在关闭时记录连接池状态

## 配置建议

### 1. 使用高并发配置文件
```bash
# 启动时使用高并发配置
java -jar bronze-gateway.jar --spring.config.location=classpath:application-high-concurrency.yml
```

### 2. JVM参数优化
```bash
# 内存配置
-Xms4g -Xmx4g

# GC优化
-XX:+UseG1GC -XX:MaxGCPauseMillis=200

# 直接内存配置
-XX:MaxDirectMemorySize=2g

# Netty优化
-Dio.netty.leakDetection.level=disabled
-Dio.netty.recycler.maxCapacity=32
```

### 3. 系统级优化
```bash
# 增加文件描述符限制
ulimit -n 65536

# 调整TCP参数
echo 'net.core.somaxconn = 65535' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_max_syn_backlog = 65535' >> /etc/sysctl.conf
sysctl -p
```

## 监控指标

### 1. 连接池状态
```java
// 在代码中调用
httpClient.logPoolStatus();
```

### 2. 关键指标
- **acquired**：已获取的连接数
- **pending**：等待获取的连接数
- **available**：可用连接数

### 3. 告警阈值
- **pending > 1000**：连接池可能不足
- **available < 10**：连接池接近耗尽
- **acquired > maxConnectionsPerHost * 0.8**：连接池使用率过高

## 故障排查

### 1. 连接池耗尽
```bash
# 检查连接池状态
grep "Pool.*acquired" application.log

# 检查等待队列
grep "pending" application.log
```

### 2. 连接泄漏
```bash
# 检查连接关闭日志
grep "Failed to release channel" application.log

# 检查连接创建日志
grep "channelCreated" application.log
```

### 3. 网络问题
```bash
# 检查网络连接
netstat -an | grep 8089

# 检查端口占用
lsof -i :8089
```

## 性能测试建议

### 1. 压力测试工具
```bash
# 使用wrk进行压力测试
wrk -t12 -c2000 -d30s http://localhost:9999/user/test

# 使用ab进行压力测试
ab -n 100000 -c 2000 http://localhost:9999/user/test
```

### 2. 监控指标
- **QPS**：每秒请求数
- **响应时间**：平均、95%、99%响应时间
- **错误率**：失败请求比例
- **连接池使用率**：连接池利用率

## 注意事项

1. **内存使用**：增加连接数会增加内存使用，需要相应调整JVM堆内存
2. **文件描述符**：大量连接会消耗文件描述符，需要调整系统限制
3. **网络带宽**：高并发会增加网络带宽使用
4. **后端服务**：确保后端服务能够处理增加的并发请求

## 回滚方案

如果优化后出现问题，可以回滚到原始配置：
```yaml
gateway:
  max-connections-per-host: 64
  max-pending-acquires: 10000
``` 