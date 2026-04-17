const { spawn } = require('child_process');
const path = require('path');
const findJavaHome = require('find-java-home');

const JAR_FILE = path.join(__dirname, 'build', 'libs', 'apkbuilder-cli.jar');

/**
 * @typedef {Object} ApkBuilderOptions
 * @property {string} autojs - AutoJs6 APK 文件路径
 * @property {string} source - 项目目录或 .js 脚本文件路径
 * @property {string} [output] - 输出 APK 路径（默认: output.apk）
 * @property {string} [workspace] - 构建工作空间目录（默认: ./build_workspace）
 * @property {string} [keystore] - 自定义 Keystore 路径
 * @property {string} [keystorePassword] - Keystore 密码
 * @property {string} [keyAlias] - Key Alias
 * @property {string} [keyPassword] - Key 密码
 * @property {string} [javaPath] - 自定义 Java 可执行文件路径
 */

/**
 * 查找 Java 环境
 * @returns {Promise<string>} Java 可执行文件路径
 */
function findJava() {
  return new Promise((resolve, reject) => {
    findJavaHome({ allowJre: true }, (err, home) => {
      if (err) {
        reject(err);
      } else {
        resolve(path.join(home, 'bin', 'java'));
      }
    });
  });
}

/**
 * 将选项转换为命令行参数数组
 * @param {ApkBuilderOptions} options
 * @returns {string[]}
 */
function optionsToArgs(options) {
  const args = [];

  args.push('--autojs', options.autojs);
  args.push('--source', options.source);

  if (options.output) {
    args.push('--output', options.output);
  }

  if (options.workspace) {
    args.push('--workspace', options.workspace);
  }

  if (options.keystore) {
    args.push('--keystore', options.keystore);
  }

  if (options.keystorePassword) {
    args.push('--keystore-password', options.keystorePassword);
  }

  if (options.keyAlias) {
    args.push('--key-alias', options.keyAlias);
  }

  if (options.keyPassword) {
    args.push('--key-password', options.keyPassword);
  }

  return args;
}

/**
 * 执行 APK 构建
 * @param {ApkBuilderOptions} options
 * @param {import('child_process').SpawnOptions} [spawnOptions]
 * @returns {Promise<void>}
 */
async function buildApk(options, spawnOptions = {}) {
  let javaPath = options.javaPath;

  if (!javaPath) {
    javaPath = await findJava();
  }

  const args = optionsToArgs(options);

  return new Promise((resolve, reject) => {
    const child = spawn(javaPath, ['-jar', JAR_FILE, ...args], {
      stdio: spawnOptions.stdio || 'inherit',
      cwd: spawnOptions.cwd || process.cwd(),
      ...spawnOptions
    });

    child.on('close', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`Process exited with code ${code}`));
      }
    });

    child.on('error', (err) => {
      reject(err);
    });
  });
}

module.exports = {
  buildApk,
  findJava,
  optionsToArgs,
  JAR_FILE
};
