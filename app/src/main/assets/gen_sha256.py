import os
import hashlib
import json
import sys

def sha256_file(path):
    sha = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            sha.update(chunk)
    return sha.hexdigest()

def generate_sha256_map(root_dir):
    result = {}
    root_dir = os.path.abspath(root_dir)

    for base, dirs, files in os.walk(root_dir):
        for name in files:
            file_path = os.path.join(base, name)
            relative_path = os.path.relpath(file_path, root_dir).replace("\\", "/")
            result[relative_path] = sha256_file(file_path)

    return result

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python gen_sha256.py <directory>")
        sys.exit(1)

    directory = sys.argv[1]
    sha_map = generate_sha256_map(directory)

    json_output = json.dumps(sha_map, indent=2, ensure_ascii=False)
    print(json_output)

    # 可选：输出到文件
    with open("sha256.json", "w", encoding="utf-8") as f:
        f.write(json_output)
        print("\nSaved to sha256.json")
