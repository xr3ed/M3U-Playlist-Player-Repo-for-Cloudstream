import re

with open('./M3UPlaylistPlayer/src/main/kotlin/com/cncverse/M3UPlaylistPlayer/M3UPlaylistPlayer.kt', 'r') as f:
    content = f.read()

# Remove the redundant description
content = content.replace(
    "val currentAndUpcoming = EpgHelper.getCurrentAndUpcomingText(progs)\n                description = currentAndUpcoming.second",
    "val currentAndUpcoming = EpgHelper.getCurrentAndUpcomingText(progs)\n                description = \"\""
)

# For the Facebook link, maybe we add an actor for the developer?
# Or just put it in the plot. The user asked "Make the Actors clickable to redirect to Facebook".
# Since Cloudstream doesn't support clickable links in actors natively, let's add it to the plot?
# Let's check where actorsList is populated.

with open('./M3UPlaylistPlayer/src/main/kotlin/com/cncverse/M3UPlaylistPlayer/M3UPlaylistPlayer.kt', 'w') as f:
    f.write(content)

print("Patched description.")
