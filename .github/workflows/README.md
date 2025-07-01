# GitHub Actions 工作流说明

本项目包含两个 GitHub Actions 工作流，用于自动化构建、测试和发布流程。

## 🚀 工作流概览

### 1. `release.yml` - 自动发布工作流

**触发条件：**
- 推送到 `master` 或 `main` 分支
- 手动触发（可选择版本递增类型）

**功能：**
- ✅ 自动版本递增（patch/minor/major）
- 🏗️ 编译和测试插件
- 🔖 创建 Git 标签
- 📦 生成 GitHub Release
- 📤 上传插件文件
- 📝 自动生成更新日志

### 2. `test.yml` - 测试和构建工作流

**触发条件：**
- Pull Request 到 `master` 或 `main` 分支
- 推送到其他分支

**功能：**
- 🧪 运行测试
- 🏗️ 构建插件
- ✅ 验证插件
- 📁 上传构建产物

## 🔧 使用方法

### 自动发布

1. **推送到主分支自动发布**：
   ```bash
   git push origin master
   ```
   - 自动递增 patch 版本（如 1.0.0 → 1.0.1）
   - 创建新的 release

2. **手动触发并选择版本类型**：
   - 在 GitHub 仓库页面：**Actions** → **Build and Release** → **Run workflow**
   - 选择版本递增类型：
     - `patch`: 1.0.0 → 1.0.1（默认）
     - `minor`: 1.0.0 → 1.1.0
     - `major`: 1.0.0 → 2.0.0

### 测试构建

1. **创建 Pull Request**：
   - 自动运行测试和构建
   - 在 PR 中查看构建状态

2. **推送到功能分支**：
   ```bash
   git checkout -b feature/new-feature
   git push origin feature/new-feature
   ```
   - 自动运行测试确保代码质量

## 📋 版本管理策略

### 语义化版本控制 (SemVer)

- **MAJOR** (X.y.z): 不兼容的 API 更改
- **MINOR** (x.Y.z): 向后兼容的新功能
- **PATCH** (x.y.Z): 向后兼容的问题修复

### 示例版本递增

| 更改类型 | 当前版本 | 新版本 | 选择类型 |
|---------|---------|--------|---------|
| Bug 修复 | 1.2.3 | 1.2.4 | patch |
| 新功能 | 1.2.3 | 1.3.0 | minor |
| 破坏性更改 | 1.2.3 | 2.0.0 | major |

## 🏷️ Release 说明

每个 release 包含：

- **🔖 版本标签**：如 `v1.0.1`
- **📦 插件文件**：`ai-commits-x.x.x.zip`
- **📝 更新日志**：基于 git commits 自动生成
- **📋 安装说明**：如何安装插件的详细步骤

## 🔍 构建产物

### Release 文件
- **插件 ZIP**：可直接安装到 JetBrains IDE
- **大小信息**：显示插件大小
- **兼容性**：支持的 IDE 版本范围

### 测试产物
- **测试报告**：在 Actions 中可下载
- **构建日志**：详细的构建过程记录
- **插件验证**：确保插件符合 JetBrains 标准

## 🚨 故障排除

### 常见问题

1. **构建失败**：
   - 检查 Java 版本兼容性
   - 确保 Gradle 配置正确
   - 查看详细的构建日志

2. **版本冲突**：
   - 确保没有重复的标签
   - 检查 build.gradle.kts 中的版本号

3. **权限问题**：
   - 确保 `GITHUB_TOKEN` 有足够权限
   - 检查仓库设置中的 Actions 权限

### 手动修复版本

如果版本号出现问题，可以手动修复：

```bash
# 删除错误的标签
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0

# 手动设置版本号
# 编辑 build.gradle.kts 中的 version = "1.0.0"
git add build.gradle.kts
git commit -m "🔖 fix version to 1.0.0"
git push origin master
```

## 📞 支持

如果遇到 GitHub Actions 相关问题：

1. 查看 [Actions 标签页](../../actions) 的详细日志
2. 检查本文档的故障排除部分
3. 在仓库中提交 Issue 报告问题 