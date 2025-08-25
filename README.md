
<p align="center">
	<img alt="logo" src="doc/logo_1.png" width="174" height="172">
</p>
<h1 align="center" style="margin: 30px 0 30px; font-weight: bold;">bronze-gateway v1.0.6</h1>
<h4 align="center">基于netty实现的轻量级、高性能网关！</h4>
<p align="center">

  <img alt="Version" src="https://img.shields.io/badge/version-1.0.6-blue.svg?cacheSeconds=2592000" />
  <a href="https://nas2.wushusong.cn/note/" target="_blank">
    <img alt="Documentation" src="https://img.shields.io/badge/documentation-yes-brightgreen.svg" />
  </a>
  <a href="#" target="_blank">
    <img alt="License: APACHE-2.0" src="https://img.shields.io/badge/License-apache2.0-yellow.svg" />
  </a>
  <a href='https://gitee.com/wushusong/bronze-gateway/stargazers'><img src='https://gitee.com/wushusong/bronze-gateway/badge/star.svg?theme=dark' alt='star'></img></a>

[![wushusong/bronze-gateway](https://gitee.com/wushusong/bronze-gateway/widgets/widget_card.svg?colors=393222,ebdfc1,fffae5,d8ca9f,393222,a28b40)](https://gitee.com/wushusong/bronze-gateway)

</p>

# 开源许可

本项目是一个开源项目，使用 Apache-2.0 许可。有关许可的详细信息，请参阅 [LICENSE](LICENSE) 文件。

# 源码

[gitee](https://gitee.com/wushusong/bronze-gateway)

[gitcode](https://gitcode.com/wushusong/bronze-gateway)

# 1、项目集成 bronze-gateway

springboot版本要求：2.7.18及以上

## 1、引入依赖

````
        <dependency>
            <groupId>io.github.wushusong</groupId>
            <artifactId>bronze-gateway-core</artifactId>
            <version>1.0.6</version>
        </dependency>
````

## 2、增加yml配置

````
gateway:
  port: 9999
  backendResponseTimeoutMs: 6000 # 后端响应超时时间
  clientWriteTimeoutMs: 6000 # 客户端写入超时时间
  connectTimeoutMs: 6000 # 连接超时时间
  maxConnectionsPerHost: 2000 # 每个主机的最大并发连接数（如果没有配置，使用合理默认值）
  maxPendingAcquires: 20000 # 每个主机最大等待连接数（如果没有配置，使用合理默认值）
  routes:
    - id: wss-test-gw # 服务名称，每个服务唯一
      path: /wss-test-gw/ # 服务访问路径，注意，前后都要斜杠，不能有*号
      instances: # 服务实例
        - serviceId: wss-test-gw-1 # 服务实例名称，同一个服务下，不同实例，serviceId要求唯一
          url: http://192.168.1.239:8089 # 服务地址，到端口一层即可
          weight: 1 # 权重，目前系统默认轮询策略
#        - serviceId: wss-test-gw-2 # 模拟服务2 该服务百分百失败
#          url: http://192.168.1.111:8089
#          weight: 1
    - id: jm-cloud-gw
      path: /jm-cloud-gw/ # 注意，前后都要斜杠，不能有*号
      loadBalancerType: roundRobinLoadBalancer # 轮询策略
      instances:
        - serviceId: jm-cloud-gw-1 # 模拟服务2 该服务是正常服务
          url: http://192.168.1.240:23500
          weight: 1
        - serviceId: jm-cloud-gw-2 # 模拟服务2 该服务百分百失败
          url: http://192.168.1.111:23500
          weight: 1
          gray: true # 灰度发布实例 当满足灰度条件的请求，都会进入该服务，否则跳转到正常服务 如下面配置的是基于请求头的灰度，当请求头有wss，则进入该服务
      # 灰度发布配置
      grayReleaseConfig:
        enabled: true
        headerBased: # 基于请求头
          headerName: VERSION # 请求头名称
          headerValues: # 请求头值
            - "wss"

  filters:
#    - name: AuthFilter  # 与@Component("AuthFilter")匹配
#      order: -100
#    - name: RateLimitFilter  # 与@Component("RateLimitFilter")匹配
#      order: -90
#      args:
#        permitsPerSecond: "1000"

#熔断配置
  resilience:
    failureRateThreshold: 50 # 失败率阈值百分比
    slowCallRateThreshold: 50 # 慢调用率阈值百分比
    slowCallDurationThreshold: 10 # 慢调用持续时间阈值
    waitDurationInOpenState: 60 # 熔断器开启状态持续时间
    permittedNumberOfCallsInHalfOpenState: 5 # 半开状态允许的调用次数
    minimumNumberOfCalls: 2 # 计算失败率所需的最小调用次数
    slidingWindowSize: 5 # 滑动窗口大小
````

# 熔断

如果需要使用熔断，需要再启动类上增加注解：[WssResilienceEnable.java](bronze-gateway-core%2Fsrc%2Fmain%2Fjava%2Fcom%2Fwss%2Fbronze%2Fgateway%2Fcore%2Fannotation%2FWssResilienceEnable.java)

# 测试

![img.png](doc/img.png)

![img_1.png](doc/img_1.png)

自定义限流，继承接口Filter

![img_2.png](doc/img_2.png)

启动

![img_3.png](doc/img_3.png)

测试

请求地址：http://192.168.1.239:9999/jm-cloud-gw/admin/tenant/getTenantById/2?a=111

会根据yml配置。匹配：jm-cloud-gw 代理到实例：jm-cloud-gw，并代理到地址 http://192.168.1.240:23500，完整代理后地址： http://192.168.1.240:23500/admin/tenant/getTenantById/2?a=111

![img_4.png](doc/img_4.png)

限流

![img_5.png](doc/img_5.png)

![img.png](doc/img_6.png)

# 有问题请issues或者添加本人qq：1193302291
