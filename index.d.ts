import { SpawnOptions } from 'child_process';

export interface ApkBuilderOptions {
    /** AutoJs6 APK 文件路径 */
    autojs: string;
    /** 项目目录或 .js 脚本文件路径 */
    source: string;
    /** 输出 APK 路径（默认: output.apk） */
    output?: string;
    /** 构建工作空间目录（默认: ./build_workspace） */
    workspace?: string;
    /** 自定义 Keystore 路径 */
    keystore?: string;
    /** Keystore 密码（默认: AutoJs6） */
    keystorePassword?: string;
    /** Key Alias（默认: AutoJs6） */
    keyAlias?: string;
    /** Key 密码（默认与 Keystore 密码相同） */
    keyPassword?: string;
    /** 自定义 Java 可执行文件路径 */
    javaPath?: string;
}

/**
 * 查找 Java 环境
 * @returns Java 可执行文件路径
 */
export function findJava(): Promise<string>;

/**
 * 将选项转换为命令行参数数组
 * @param options 构建选项
 * @returns 命令行参数数组
 */
export function optionsToArgs(options: ApkBuilderOptions): string[];

/**
 * 执行 APK 构建
 * @param options 构建选项
 * @param spawnOptions 子进程选项
 * @returns Promise，构建完成后 resolve
 */
export function buildApk(options: ApkBuilderOptions, spawnOptions?: SpawnOptions): Promise<void>;

/** JAR 文件路径 */
export const JAR_FILE: string;
