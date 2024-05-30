<div align="center">
<h1>AD Filter Subscriber</h1>
  <p>
    广告过滤规则订阅器，整合不同来源的规则，帮助你快速构建属于自己的规则集~
  </p>
<!-- Badges -->
<p>
  <img src="https://img.shields.io/github/last-commit/fordes123/ad-filters-subscriber?style=flat-square" alt="last update" />
  <img src="https://img.shields.io/github/forks/fordes123/ad-filters-subscriber?style=flat-square" alt="forks" />
  <img src="https://img.shields.io/github/stars/fordes123/ad-filters-subscriber?style=flat-square" alt="stars" />
  <img src="https://img.shields.io/github/issues/fordes123/ad-filters-subscriber?style=flat-square" alt="open issues" />
  <img src="https://img.shields.io/github/license/fordes123/ad-filters-subscriber?style=flat-square" alt="license" />
</p>

<h4>
    <a href="#a">项目说明</a>
  <span> · </span>
    <a href="#b">快速开始</a>
  <span> · </span>
    <a href="#c">规则订阅</a>
  <span> · </span>
    <a href="#d">问题反馈</a>
  </h4>
</div>

<br/>
<h2 id="a">📔 项目说明</h2>

本项目旨在聚合不同来源、不同格式的广告过滤规则，自由的进行转换和整合。

### 支持的规则格式
- [x] easylist
- [ ] dnsmasq
- [ ] clash
- [ ] smartdns
- [x] hosts

> ⚠️ 新版不再兼容原配置格式，迁移前务必注意

<br/>
<h2 id="b">🛠️ 快速开始</h2>

### 示例配置

```yaml
application:
  rule:
    #远程规则订阅，path为 http、https地址
    remote:
      - name: 'Subscription 1'               #可选参数: 规则名称，如无将使用 path 作为名称
        path: 'https://example.org/rule.txt' #必要参数: 规则url，仅支持 http/https，不限定响应内容格式
        type:  easylist                      #可选参数: 规则类型：easylist (默认)、dnsmasq、clash、smartdns、hosts

    #本地规则，path为 操作系统支持的绝对或相对路径
    local:
      - name: 'private rule'
        path: '/rule/private.txt'

  output:
    #文件头配置，将自动作为注释添加至每个规则文件开始
    #可使用占位符 ${name}、${type}、${desc} 以及 ${date} (当前日期)
    file_header: |
      ADFS Adblock List
      Title: ${name}
      Last Modified: ${date}
      Homepage: https://github.com/fordes123/ad-filters-subscriber/
    path: rule   #规则文件输出路径，相对路径默认为程序所在路径
    files:
      - name: easylist.txt     #必要参数: 文件名
        type: EASYLIST         #必要参数: 文件类型: easylist、dnsmasq、clash、smartdns、hosts
        desc: 'ADFS EasyList'  #可选参数: 文件描述，可在file_header中通过 ${} 中使用
```

---
本程序基于 `Java21` 编写，使用 `Maven` 进行构建，你可以参照示例配置，编辑 `src/main/resources/application.yml`
，并通过以下任意一种方式快速开始：

#### **本地调试**

```bash
git clone https://github.com/fordes123/ad-filters-subscriber.git
cd ad-filters-subscriber
mvn clean
mvn spring-boot:run
```

#### **Github Action**

- fork 本项目
- 自定义规则订阅 (可选)
    - 参照示例配置，修改配置文件: `src/main/resources/application.yml`
- 打开 `Github Action` 页面，选中左侧 `Update Filters` 授权 `Workflow` 定时执行(⚠ 重要步骤)
- 点击 `Run workflow` 或等待自动执行。执行完成后相应规则生成在配置中指定的目录下

#### **Codespaces**

- 登录 `Github`，点击本仓库右上角 `Code` 按钮，选择并创建新的 `Codespaces`
- 等待 `Codespaces` 启动，即可直接对本项目进行调试

### 如何更新

当源代码存在更新时，(你的)仓库首页会出现如下图提示:
<img src="./screen.png">

此时选择 **Sync fork** 再选择 **Update branch** 即可同步更新.  
(如曾修改过源代码，那么合并可能存在冲突，请谨慎处理)

<br/>
<h2 id="c">🎯 规则订阅</h2>

**⚠ 本仓库不再提供规则订阅，我们更推荐 fork 本项目自行构建规则集.**

下面是使用了本项目进行构建的规则仓库，可在其中寻找合适的规则订阅:
<details>
<summary>点击查看</summary>
<ul>
    <li><a href="https://github.com/xndeye/adblock_list/">xndeye/adblock_list</a></li>
    <p>欢迎提交 issues 或 pr 留下你的仓库地址~</p>
</ul>
</details>

<br/>
<h2 id="d">💬 问题反馈</h2>

- 👉 [issues](https://github.com/fordes123/ad-filters-subscriber/issues)
