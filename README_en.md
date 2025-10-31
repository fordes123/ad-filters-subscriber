<div align="center">
<h1>AD Filter Subscriber</h1>
  <p>
    Ad Filter Rule Subscriber, integrating rules from various sources to help you quickly build your own rule set~
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
    <a href="#a">Introduction</a>
  <span> · </span>
    <a href="#b">Quick Start</a>
  <span> · </span>
    <a href="#c">Rule Subscription</a>
  <span> · </span>
    <a href="#d">Feedback</a>
  </h4>
</div>
<br/>

English | [中文](./README.md)
<h2 id="a">📔 Introduction</h2>

This project aims to aggregate ad filtering rules from different sources and in various formats, allowing for flexible
conversion and integration.
> ⚠️ Note: The new version is not compatible with the original configuration format, so please be cautious before
> migrating.

#### Supported Rule Formats 

- [x] easylist
- [x] dnsmasq
- [x] clash
- [x] smartdns
- [x] hosts

#### Important Notes 

1. Only basic rule conversions are supported, specifically rules consisting of domain names and wildcard domains. Rules
   such as `||example.org^$popup` cannot be converted (merging and deduplication are not affected). 
2. Accept unavoidable limitations. For example, `||example.org^` will block example.org and all its subdomains, but when
   converted to hosts format, it will not match subdomains. 
3. Rule validity check is based on domain resolution, so it only supports basic rules.

<h2 id="b">🛠️ Quick Start</h2>

### Example Configuration

```yaml
application:

  # Input configuration
  input:
    - name: 'Subscription 1'               # Optional parameter: rule name, will use path as name if not specified
      path: 'https://example.org/rule.txt' # Required parameter: rule url (http/https) or local file location (absolute/relative path)
      type: easylist                       # Optional parameter: rule type: easylist (default), dnsmasq, clash, smartdns, hosts

    - name: 'Subscription 2'
      path: 'rule/local.txt'
      type: hosts

  # Output configuration
  output:
    # File header configuration, will be automatically added as comments at the beginning of each rule file
    # Available placeholders: ${name}, ${type}, ${desc}, ${date} (current date), ${total} (total number of rules)
    file_header: |
      ADFS AdBlock ${type}
      Last Modified: ${date}
      Total Size: ${total}
      Homepage: https://github.com/fordes123/ad-filters-subscriber/

    files:
      - name: easylist.txt     # Required parameter: file name
        type: easylist         # Required parameter: file type: easylist, dnsmasq, clash, smartdns, hosts
        file_header:           # Optional parameter: file header configuration, will be automatically added as comments at the beginning of each rule file (takes precedence over output.file_header)
        desc: 'ADFS EasyList'  # Optional parameter: file description, can be used in file_header with ${}
        filter:
          - basic              # Basic rules, without any control or matching symbols, can be converted to hosts
          - wildcard           # Wildcard rules, using wildcards only
          - unknown            # Other rules, such as those using regex or advanced modifiers, these rules currently cannot be converted to other formats
        rule:                  # Optional parameter: specify the rule sources to be used for this file, if not specified, all rule sources in input will be used
          - Subscription 1
          - Subscription 2
```

---
This program is written in `Java 21` and built using `Maven`. You can refer to the [example configuration](./config/application-example.yaml),
edit `config/application.yaml`, and quickly get started using any of the following methods:

#### **Local Debugging**

```bash
git clone https://github.com/fordes123/ad-filters-subscriber.git
cd ad-filters-subscriber
mvn clean
mvn spring-boot:run
```

#### **Github Action**

- Fork this project
- Customize rule subscriptions
    - Refer to the [example configuration](./config/application-example.yaml) and modify the configuration file: `config/application.yaml`
- Open the GitHub Actions page, select Update Filters on the left side, and authorize the workflow for scheduled
  execution (⚠ important step)
- Click Run workflow or wait for automatic execution. Once completed, the corresponding rules will be generated in the
  directory specified in the configuration.

#### **Codespaces**

- Log in to `GitHub`, click the `Code` button in the upper right corner of this repository, and select and create a
  new `Codespace`.
- Wait for `Codespaces` to start, and you can directly debug this project.

<h2 id="c">🎯 Rule Subscription</h2>

> ⚠ This repository no longer provides rule subscriptions. We highly recommend forking this project to build your own
> rule set.

Below are rule repositories built using this project. You can find suitable rule subscriptions in them:

<details>
<summary>Click to view</summary>
<ul>
<br/>
<li><a href="https://github.com/xndeye/adblock_list/">xndeye/adblock_list</a></li>
</ul>
</details>

<h2 id="d">💬 Feedback</h2>

- 👉 [issues](https://github.com/fordes123/ad-filters-subscriber/issues)
