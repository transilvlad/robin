package com.mimecast.robin.queue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Batch of queue mutations plus newly derived queue items.
 *
 * @param <T> payload type
 */
public final class QueueMutationBatch<T extends Serializable> {
    private final List<QueueMutation<T>> mutations;
    private final List<T> newItems;

    public QueueMutationBatch(List<QueueMutation<T>> mutations, List<T> newItems) {
        this.mutations = mutations == null ? List.of() : List.copyOf(mutations);
        this.newItems = newItems == null ? List.of() : List.copyOf(newItems);
    }

    public static <T extends Serializable> QueueMutationBatch<T> empty() {
        return new QueueMutationBatch<>(List.of(), List.of());
    }

    public List<QueueMutation<T>> mutations() {
        return mutations;
    }

    public List<T> newItems() {
        return newItems;
    }

    public boolean isEmpty() {
        return mutations.isEmpty() && newItems.isEmpty();
    }

    public static <T extends Serializable> Builder<T> builder() {
        return new Builder<>();
    }

    public static final class Builder<T extends Serializable> {
        private final List<QueueMutation<T>> mutations = new ArrayList<>();
        private final List<T> newItems = new ArrayList<>();

        public Builder<T> addMutation(QueueMutation<T> mutation) {
            if (mutation != null) {
                mutations.add(mutation);
            }
            return this;
        }

        public Builder<T> addMutations(List<QueueMutation<T>> additionalMutations) {
            if (additionalMutations != null) {
                additionalMutations.stream().filter(java.util.Objects::nonNull).forEach(mutations::add);
            }
            return this;
        }

        public Builder<T> addNewItem(T item) {
            if (item != null) {
                newItems.add(item);
            }
            return this;
        }

        public Builder<T> addNewItems(List<T> items) {
            if (items != null) {
                items.stream().filter(java.util.Objects::nonNull).forEach(newItems::add);
            }
            return this;
        }

        public List<QueueMutation<T>> previewMutations() {
            return Collections.unmodifiableList(mutations);
        }

        public int size() {
            return mutations.size();
        }

        public boolean isEmpty() {
            return mutations.isEmpty() && newItems.isEmpty();
        }

        public QueueMutationBatch<T> build() {
            return new QueueMutationBatch<>(mutations, newItems);
        }

        public void clear() {
            mutations.clear();
            newItems.clear();
        }
    }
}
