package com.hdz.wearcontrol;

import java.util.List;

/** Profile enumeration order has no bearing on the chosen peer. */
final class Selection {
    enum Result { SELECTED, NONE, AMBIGUOUS, INCOMPLETE, PIN_UNAVAILABLE }
    static final class Peer {
        final String address;
        final boolean bonded, phone, active, known, sink;
        Peer(String address, boolean bonded, boolean phone, boolean active, boolean known, boolean sink) {
            this.address = address;
            this.bonded = bonded;
            this.phone = phone;
            this.active = active;
            this.known = known;
            this.sink = sink;
        }
    }
    final Result result;
    final String address;
    private Selection(Result result, String address) { this.result = result; this.address = address; }
    static Selection choose(String pin, boolean complete, List<Peer> peers) {
        boolean pinned = pin != null && !pin.isEmpty();
        String winner = null;
        int best = -1, ties = 0;
        for (Peer peer : peers) {
            if (!peer.bonded || peer.phone) continue;
            if (pinned && pin.equalsIgnoreCase(peer.address)) return new Selection(Result.SELECTED, peer.address);
            if (!peer.active) continue;
            int rank = (peer.known ? 2 : 0) + (peer.sink ? 1 : 0);
            if (rank > best) { best = rank; ties = 1; winner = peer.address; }
            else if (rank == best) ties++;
        }
        if (pinned) return new Selection(Result.PIN_UNAVAILABLE, null);
        if (!complete) return new Selection(Result.INCOMPLETE, null);
        if (ties > 1) return new Selection(Result.AMBIGUOUS, null);
        return winner == null ? new Selection(Result.NONE, null) : new Selection(Result.SELECTED, winner);
    }
}
