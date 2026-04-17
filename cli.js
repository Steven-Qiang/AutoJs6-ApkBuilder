const { spawn } = require('child_process');
const path = require('path');
const findJavaHome = require('find-java-home');

const JAR_FILE = path.join(__dirname, 'build', 'libs', 'apkbuilder-cli.jar');

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
 * @author StevenXu
 * @param {string} javaPath javaPath
 * @param {string[]} args apk builder arguments
 * @return {Promise<void>}
 */
function runApkBuilder(javaPath, args) {
  return /** @type {Promise<void>} */(new Promise((resolve, reject) => {
    const pcs = spawn(javaPath, ['-jar', JAR_FILE, ...args], {
      stdio: 'inherit',
      cwd: process.cwd()
    });

    pcs.on('close', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`Process exited with code ${code}`));
      }
    });

    pcs.on('error', (err) => {
      reject(err);
    });
  }));
}

async function main() {
  const args = process.argv.slice(2);

  try {
    const javaPath = await findJava();
    await runApkBuilder(javaPath, args);
  } catch (/** @type {any} */err) {
    console.error('Error:', err.message);
    process.exit(1);
  }
}

main();
