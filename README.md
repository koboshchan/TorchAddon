TorchAddon for Minecraft 26.3

Build with Java 25 and ./gradlew build. Build koboshchan/Wurst7 branch 26.3-addons first, either as wurst7-base or beside this checkout at ../Wurst7. The included Wurst build is optional when the sibling jar already exists. This is a Wurst plugin, not a standalone torch placer.

Enable TorchPlanner, right-click each corner and press Enter to confirm it. Shift selects the adjacent block. The existing Checks per tick and Render limit settings are unchanged.

The planner targets creeper-compatible spawn geometry with measured block light zero. Existing torches, lanterns and other block lights are respected, while sunlight is intentionally ignored so the plan remains useful at night. It models a normal torch's level-14 light using six-direction propagation and Minecraft 26.3's own dampening and voxel-face occlusion rules. Light can bend around corners instead of relying on straight-line visibility.

Only empty, dry positions where an actual floor or wall torch can survive are considered. Each suggestion must have a visible supporting face within interaction range of a reachable standing position. Reachability starts at the player's feet and follows supported, collision-free walking/jumping routes within the selection plus a small margin. This is deliberately conservative. Slabs, swimming, ladders, flight, paths outside that margin and unsupported starting positions can leave spots uncovered. Stand on a full block near or inside the selection before calculating.

Coverage is selected greedily by newly lit dark spawn spots, followed by redundant-torch removal. It is a heuristic, not a proof of the minimum possible number of torches. Uncovered spots are reported rather than treated as safe. Other mobs with special spawning rules are not modeled. Unloaded cells are never assumed transparent or reachable.

World sampling and light propagation run in bounded tick slices on the client thread. Only immutable coverage data goes to the cancellable background solver. Large coverage maps fail with a request to select a smaller area rather than growing without a bound. The plan is a snapshot. Recalculate after changing terrain or lighting, and use normal torches. Soul and redstone torches do not mark a suggestion complete.

The Java ServiceLoader entry registers TorchPlanner through net.wurstclient.addon.Addon. Tests run as part of ./gradlew build and cover measured-light eligibility, falloff, attenuation, walls, corner paths, incremental calculation, deterministic greedy selection, pruning, cancellation and uncovered targets. No game launch is part of the build.
