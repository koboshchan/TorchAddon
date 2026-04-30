package com.kobosh.torchaddon.client;

import net.wurstclient.addon.Addon;
import net.wurstclient.command.Command;
import net.wurstclient.hack.Hack;
import com.kobosh.torchaddon.client.hack.ExampleHack;
import com.kobosh.torchaddon.client.hack.TorchPlannerHack;

/**
 * WurstAddon provider for registering hacks and commands with Wurst7.
 */
public class WurstAddonHackAddon implements Addon {

    private final Hack[] hacks = {
        // new ExampleHack(),
        new TorchPlannerHack()
    };

    @Override
    public String getAddonName() {
        return "TorchAddon";
    }

    @Override
    public Hack[] getHacks() {
        return hacks;
    }

    @Override
    public Command[] getCommands() {
        return new Command[0];
    }
}
