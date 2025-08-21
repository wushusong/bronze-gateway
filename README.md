
<p align="center">
	<img alt="logo" src="doc/logo_1.png" width="150" height="150">
</p>
<h1 align="center" style="margin: 30px 0 30px; font-weight: bold;">bronze-gateway v1.0.0</h1>
<h4 align="center">基于netty实现的轻量级、高性能网关！</h4>
<p align="center">

  <img alt="Version" src="https://img.shields.io/badge/version-1.0.0-blue.svg?cacheSeconds=2592000" />
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
            <version>1.0.0</version>
        </dependency>
````

## 2、增加yml配置

````
gateway:
  port: 9999 # 外部访问端口
  routes:
    - id: user-service # 服务名称，每个服务唯一
      path: /user/ # 服务访问路径，注意，前后都要斜杠，不能有*号
      uri: http://localhost:8081 # 服务地址，到端口一层即可，预留，目前没有使用
      order: 0 # 如果有多个路由，order值越小优先级越高
      instances: # 服务实例
        - serviceId: user-service # 服务实例名称，同一个服务下，不同实例，serviceId配置一样
          url: http://localhost:8081 # 服务地址，到端口一层即可
          weight: 1 # 权重，预留，目前系统默认轮询策略
          healthy: true # 服务健康状态，预留，目前默认为true
    - id: jm-cloud-gw
      path: /jm-cloud-gw/ # 注意，前后都要斜杠，不能有*号
      uri: http://192.168.1.240:23500
      order: 0
      instances:
        - serviceId: jm-cloud-gw
          url: http://192.168.1.240:23500
          weight: 1
          healthy: true
        - serviceId: jm-cloud-gw
          url: http://192.168.1.240:23500
          weight: 1
          healthy: true
  filters: # 过滤器配置
#    - name: AuthFilter  # 与@Component("AuthFilter")匹配
#      order: -100
    - name: RateLimitFilter  # 与@Component("RateLimitFilter")匹配 需要自己实现过滤器，实现接口com.wss.bronze.gateway.core.filter.Filter
      order: -90 # 配置顺序，越小越先执行
      args: # 参数，Object... args
        permitsPerSecond: "1000"
````

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

