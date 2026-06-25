import re

with open('./M3UPlaylistPlayer/src/main/kotlin/com/cncverse/M3UPlaylistPlayer/M3UPlaylistPlayer.kt', 'r') as f:
    content = f.read()

# Cloudstream Actor UI has a main image and text. But wait, if we use LiveStreamLoadResponse, can we pass the Facebook link somewhere?
# No direct support for click actions on Actors. Let's add the link to the description / plot since the description is where you can usually click links, or just leave it empty and add an Actor for Facebook.
content = content.replace(
    'description = ""',
    'description = "Pesbuk: https://www.facebook.com/pesbuk.ibal"'
)

with open('./M3UPlaylistPlayer/src/main/kotlin/com/cncverse/M3UPlaylistPlayer/M3UPlaylistPlayer.kt', 'w') as f:
    f.write(content)

print("Patched description with facebook link.")
