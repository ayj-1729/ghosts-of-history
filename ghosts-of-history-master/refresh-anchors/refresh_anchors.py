# This script refreshes the TTL (time to live) of all the anchors to 2030.
# Add oauth2l to PATH for this to work if you're not on Windows: https://github.com/google/oauth2l

# Read more about why this script is needed here: https://stackoverflow.com/q/67214491/6120487
# Make sure to manually run it at least once a year, otherwise we can lose some anchors!

import subprocess
import requests


def main():
    bearer_token = subprocess.run(
        ["oauth2l", "fetch", "--json", "ghosts-of-history-ba6b68332abd.json", "arcore.management"],
        stdout=subprocess.PIPE
    ).stdout.decode('utf-8').strip()

    while True:
        anchors = requests.get(
            "https://arcore.googleapis.com/v1beta2/management/anchors?page_size=50&order_by=expire_time%20desc",
            headers={"Authorization": f"Bearer {bearer_token}"}
        ).json()
        print(anchors)
        updated_at_least_one_anchor = False
        for anchor in anchors['anchors']:
            old_expire_time: str = anchor["expireTime"]
            if old_expire_time.startswith("2030"):
                continue
            print(f"Expire time of {anchor["name"]} is {old_expire_time}, updating to 2030")
            updated_at_least_one_anchor = True
            new_expire_time = "2030" + old_expire_time[4:]
            anchor_name = anchor['name'].split("/")[1]
            patch_response = requests.patch(
                f"https://arcore.googleapis.com/v1beta2/management/anchors/{anchor_name}?updateMask=expire_time",
                data=f'{{ expireTime: "{new_expire_time}"}} ',
                headers={"Authorization": f"Bearer {bearer_token}"}
            )
            print(patch_response.status_code)

        if not updated_at_least_one_anchor:
            break
    print("All anchors have their time to live in 2030. No work to be done.")


if __name__ == "__main__":
    main()
