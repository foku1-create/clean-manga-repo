package cleanmanga.guard;

/** Thrown instead of handing out the pages of a blocked manga. */
public final class BlockedException extends RuntimeException {
    public BlockedException() {
        super("Blocked by your clean manga filter");
    }
}
