package gvlfm78.plugin.OldCombatMechanics.utilities.packet;

import gvlfm78.plugin.OldCombatMechanics.utilities.Messenger;
import gvlfm78.plugin.OldCombatMechanics.utilities.reflection.Reflector;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A simple packet injector, to modify the packets sent and received
 */
class PacketInjector extends ChannelDuplexHandler {

    private volatile boolean isClosed;
    private Channel channel;
    // There are a lot more reads than writes, so performance should be okay
    private List<PacketListener> packetListeners = new CopyOnWriteArrayList<>();
    private WeakReference<Player> playerWeakReference;

    /**
     * Must be detached manually!
     *
     * @param player The player to attach into
     */
    PacketInjector(Player player){
        Objects.requireNonNull(player, "player can not be null!");

        playerWeakReference = new WeakReference<>(player);

        try{
            attach(player);
        } catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Attaches to a player
     *
     * @param player The player to attach to
     */
    private void attach(Player player) throws Exception{

        Object playerConnection = PacketSender.getInstance().getConnection(player);

        Object manager = Reflector.getFieldValue(playerConnection, "networkManager");

        channel = (Channel) Reflector.getFieldValue(manager, "channel");

        // remove old listener, if it wasn't properly cleared up
        if(channel.pipeline().get("ocm_handler") != null){
            debug("Old listener lingered around");

            ChannelHandler old = channel.pipeline().get("ocm_handler");
            if(old instanceof PacketInjector){
                debug("Detaching old listener");
                ((PacketInjector) old).detach();
            }

            // remove old
            channel.pipeline().remove("ocm_handler");
        }

        try{
            channel.pipeline().addBefore("packet_handler", "ocm_handler", this);
        } catch(NoSuchElementException e){
            throw new NoSuchElementException("No base handler found. Was the player instantly disconnected?");
        }
    }

    /**
     * Removes this handler
     */
    void detach(){
        debug("Detaching injector... (%d)", hashCode());
        if(isClosed || !channel.isOpen()){
            debug("Closed(%b) or channel closed(%b) already! (%d)", isClosed, !channel.isOpen(), hashCode());
            return;
        }
        channel.eventLoop().submit(() -> {
            channel.pipeline().remove(this);

            // only clear the channel after the last access
            channel = null;
            isClosed = true;

            // clear references. Probably not needed, but I am not sure about the
            // channel.
            playerWeakReference.clear();
            packetListeners.clear();

            debug("Injector successfully detached (%d)", hashCode());
        });
    }

    /**
     * Adds a {@link PacketListener}
     *
     * @param packetListener The {@link PacketListener} to add
     * @throws IllegalStateException if the channel is already closed
     */
    void addPacketListener(PacketListener packetListener){
        Objects.requireNonNull(packetListener, "packetListener can not be null");
        if(isClosed){
            throw new IllegalStateException("Channel already closed. Adding of listener invalid");
        }
        packetListeners.add(packetListener);
    }

    /**
     * Removes a {@link PacketListener}
     *
     * @param packetListener The {@link PacketListener} to remove
     */
    void removePacketListener(PacketListener packetListener){
        packetListeners.remove(packetListener);
    }

    /**
     * Returns the amount of listeners
     *
     * @return The amount of listeners
     */
    int getListenerAmount(){
        return packetListeners.size();
    }

    @Override
    public void write(ChannelHandlerContext channelHandlerContext, Object packet, ChannelPromise channelPromise)
            throws Exception{

        if(playerWeakReference == null || playerWeakReference.get() == null){
            debug(
                    "playerWeakReference or its value is null. This should NOT happen at this stage. " +
                            "(write@%d)", hashCode()
            );
            detach();

            // bubble up
            super.write(channelHandlerContext, packet, channelPromise);
            return;
        }

        PacketEvent event = new PacketEvent(
                packet,
                PacketEvent.ConnectionDirection.TO_CLIENT,
                playerWeakReference.get()
        );

        for(PacketListener packetListener : packetListeners){
            try{
                if(!isClosed){
                    packetListener.onPacketSend(event);
                }
            } catch(Exception e){
                Messenger.warn(e, "Error in a packet listener (send).");
            }
        }

        // let it through
        if(!event.isCancelled()){
            super.write(channelHandlerContext, packet, channelPromise);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext channelHandlerContext, Object packet) throws Exception{
        if(playerWeakReference == null || playerWeakReference.get() == null){
            debug(
                    "playerWeakReference or its value is null. This should NOT happen at this stage. " +
                            "(read@%d)", hashCode()
            );
            detach();

            // bubble up
            super.channelRead(channelHandlerContext, packet);
            return;
        }

        PacketEvent event = new PacketEvent(
                packet,
                PacketEvent.ConnectionDirection.TO_SERVER,
                playerWeakReference.get()
        );

        for(PacketListener packetListener : packetListeners){
            try{
                if(!isClosed){
                    packetListener.onPacketReceived(event);
                }
            } catch(Exception e){
                Messenger.warn(e, "Error in a packet listener (receive).");
            }
        }

        // let it through
        if(!event.isCancelled()){
            super.channelRead(channelHandlerContext, packet);
        }
    }

    private static void debug(String message, Object... formatArgs){
        Messenger.debug("PacketInjector: " + message, formatArgs);
    }
}
