import os
import hashlib
import zipfile
import json
import argparse
import sys

# 使用 SHA-256 在速度和安全性上取得了很好的平衡。
HASH_ALGORITHM = hashlib.sha256

def generate_manifest(zip_path):
    """
    为 zip 压缩包的内容生成一个 JSON 清单文件。
    清单格式为 { "zip压缩包内的文件路径": "sha256_hash" }。
    """
    if not os.path.exists(zip_path):
        print(f"错误: 在 '{zip_path}' 未找到 Zip 文件", file=sys.stderr)
        sys.exit(1)

    # 生成的清单文件将与 zip 文件同名，但扩展名为 .json
    manifest_path = os.path.splitext(zip_path)[0] + '.json'
    print(f"正在为 '{os.path.basename(zip_path)}' 生成清单文件...")

    manifest_data = {}

    try:
        with zipfile.ZipFile(zip_path, 'r') as zf:
            # --- 这是修改的部分 ---
            # 获取所有文件条目的文件名列表
            file_names = [info.filename for info in zf.infolist() if not info.is_dir()]
            # 对文件名进行排序
            sorted_file_names = sorted(file_names)
            # --- 修改结束 ---

            # 遍历排序后的文件名列表
            for filename in sorted_file_names:
                # 在 zip 包内打开文件以读取其内容
                with zf.open(filename) as f:
                    file_content = f.read()
                    file_hash = HASH_ALGORITHM(file_content).hexdigest()

                # 使用正斜杠以确保跨平台的路径一致性
                normalized_path = filename.replace('\\', '/')
                manifest_data[normalized_path] = file_hash
                print(f"  - 已哈希: {normalized_path}")

        # 将清单数据写入 JSON 文件
        with open(manifest_path, 'w', encoding='utf-8') as f:
            # 使用 sort_keys=True 确保 JSON 文件的 key (也就是文件名) 是有序的
            json.dump(manifest_data, f, indent=2, sort_keys=True)

        print(f"\n成功! 清单文件已生成于: '{manifest_path}'")

    except zipfile.BadZipFile:
        print(f"错误: '{zip_path}' 不是一个有效的 zip 文件。", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"发生未知错误: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="为一个 zip 文件生成 SHA-256 清单。"
    )
    parser.add_argument(
        "zip_file_path",
        help="你的 assets 文件夹中 zip 文件的完整路径。"
    )
    args = parser.parse_args()

    generate_manifest(args.zip_file_path)