"""
Smoke test for fiji-mcp bridge.

Prerequisites:
  1. Fiji is running with fiji-mcp-bridge plugin started
     (Plugins > fiji-mcp > Start Bridge)
  2. Run: uv run python tests/smoke_test.py
"""

import asyncio
import json
import sys

from fiji_mcp.fiji_client import FijiClient, FijiError


async def main():
    client = FijiClient()
    print("Connecting to Fiji...")
    try:
        await client.connect()
    except ConnectionError as e:
        print(f"FAIL: {e}")
        sys.exit(1)
    print("Connected!\n")

    # 1. Status
    print("── status ──")
    result = await client.send_request("status")
    print(json.dumps(result, indent=2))

    # 2. List commands (search for "blur")
    print("\n── list_commands (blur) ──")
    result = await client.send_request("list_commands", {"pattern": "blur"})
    print(f"Found {result.get('count', 0)} commands matching 'blur'")

    # 3. List open images
    print("\n── list_images ──")
    result = await client.send_request("list_images")
    images = result.get("images", [])
    print(f"{len(images)} images open")
    for img in images:
        print(f"  - {img['title']} ({img['width']}x{img['height']})")

    # 4. Run a macro
    print("\n── run_ij_macro ──")
    result = await client.send_request("run_ij_macro", {
        "code": 'newImage("Test", "8-bit", 256, 256, 1);'
    })
    print(f"Macro result: {result}")

    # 5. Verify image was created
    print("\n── list_images (after macro) ──")
    result = await client.send_request("list_images")
    images = result.get("images", [])
    test_found = any(img["title"] == "Test" for img in images)
    print(f"Test image created: {test_found}")

    # 6. Get image info
    if test_found:
        print("\n── get_image_info ──")
        result = await client.send_request("get_image_info", {"title": "Test"})
        print(json.dumps(result, indent=2))

    # 7. Get log
    print("\n── get_log ──")
    result = await client.send_request("get_log", {"count": 5})
    for line in result.get("lines", []):
        print(f"  {line}")

    # 8. Clean up
    print("\n── cleanup ──")
    await client.send_request("run_ij_macro", {
        "code": 'selectImage("Test"); close();'
    })
    print("Closed test image")

    await client.disconnect()
    print("\n✓ Smoke test passed!")


if __name__ == "__main__":
    asyncio.run(main())
