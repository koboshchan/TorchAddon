package com.kobosh.torchaddon.client.hack;

import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.SearchTags;

/**
 * Example hack that sends a message in chat and then disables itself
 */

 @SearchTags({"example"})
public class ExampleHack extends Hack {

    public ExampleHack() {
        super("ExampleHack");
        setCategory(Category.FUN);
    }

    @Override
    protected void onEnable() {
        ChatUtils.message("Example Hack enabled! This message will be sent once.");

        // Disable the hack after sending the message
        setEnabled(false);
    }

    @Override
    protected void onDisable() {
        // Cleanup if needed
    }
}
