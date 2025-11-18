import os
import hashlib
import argparse
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

# 定义SHA256计算中读取文件的块大小，8KB是一个比较均衡的选择
CHUNK_SIZE = 8192

def calculate_sha256(filepath):
    """计算单个文件的SHA256哈希值"""
    sha256_hash = hashlib.sha256()
    try:
        with open(filepath, "rb") as f:
            # 逐块读取文件以节省内存，特别适用于大文件
            for byte_block in iter(lambda: f.read(CHUNK_SIZE), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()
    except IOError as e:
        print(f"错误: 无法读取文件 '{filepath}': {e}", file=sys.stderr)
        return None

def process_file(filepath):
    """处理单个文件：计算哈希并写入.sha256文件"""
    print(f"正在处理: {os.path.basename(filepath)}...")
    hex_digest = calculate_sha256(filepath)
    if hex_digest:
        sha256_filename = filepath + ".sha256"
        try:
            with open(sha256_filename, "w") as f:
                # 格式为: <哈希值> <空格><空格> <文件名>
                # 这是 `shasum -a 256` 命令的标准输出格式，兼容性好
                f.write(f"{hex_digest}  {os.path.basename(filepath)}\n")
            return f"成功: {os.path.basename(sha256_filename)}"
        except IOError as e:
            return f"错误: 无法写入SHA256文件 '{sha256_filename}': {e}"
    return None

def main():
    """主函数，解析参数并处理文件夹"""
    parser = argparse.ArgumentParser(
        description="为指定文件夹下的所有文件生成独立的 .sha256 校验文件。"
    )
    parser.add_argument(
        "directory",
        metavar="TARGET_DIRECTORY",
        type=str,
        help="需要处理的目标文件夹路径。"
    )
    parser.add_argument(
        "-r", "--recursive",
        action="store_true",
        help="如果设置此项，将递归处理所有子文件夹中的文件。"
    )
    parser.add_argument(
        "-w", "--workers",
        type=int,
        default=os.cpu_count() or 4,
        help="用于并行处理文件的线程数 (默认: 系统CPU核心数)。"
    )

    args = parser.parse_args()
    target_dir = args.directory

    if not os.path.isdir(target_dir):
        print(f"错误: 提供的路径 '{target_dir}' 不是一个有效的文件夹。", file=sys.stderr)
        sys.exit(1)

    files_to_process = []
    if args.recursive:
        print(f"正在递归扫描文件夹: {target_dir}")
        for root, _, filenames in os.walk(target_dir):
            for filename in filenames:
                # 忽略已经存在的 .sha256 文件
                if not filename.endswith(".sha256"):
                    files_to_process.append(os.path.join(root, filename))
    else:
        print(f"正在扫描文件夹: {target_dir}")
        for filename in os.listdir(target_dir):
            filepath = os.path.join(target_dir, filename)
            # 确保是文件且不是 .sha256 文件
            if os.path.isfile(filepath) and not filename.endswith(".sha256"):
                files_to_process.append(filepath)

    if not files_to_process:
        print("未找到需要处理的文件。")
        return

    print(f"找到 {len(files_to_process)} 个文件。将使用 {args.workers} 个线程进行处理...")

    # 使用线程池并行处理文件，大大提高效率
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(process_file, filepath) for filepath in files_to_process]
        for future in as_completed(futures):
            result = future.result()
            if result:
                print(result)

    print("\n所有文件处理完毕！")

if __name__ == "__main__":
    main()