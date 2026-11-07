package dynamitedb.sm.index;

import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.DbmsException;
import dynamitedb.contracts.storage.ErrorCode;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


public final class BPlusTreeIndex implements EqualityIndex, OrderedIndex {

  private static final int MAX_KEYS = 32; // Maksimum jumlah key per node
  private static final int MIN_KEYS = MAX_KEYS / 2; // Minimum jumlah key per node non-root
  private static final int NO_SIBLING = -1;

  private final List<Boolean> leafFlag = new ArrayList<>();
  private final List<List<Value>> nodeKeys = new ArrayList<>();
  private final List<List<List<RecordId>>> nodeRids = new ArrayList<>();
  private final List<List<Integer>> nodeChildren = new ArrayList<>();
  private final List<Integer> nextSibling = new ArrayList<>();
  private final List<Integer> freeNodes = new ArrayList<>();

  private int root;

  private int splitRightId = NO_SIBLING;
  private Value splitSeparator;

  public BPlusTreeIndex() {
    root = allocateNode(true);
  }

  @Override
  public synchronized List<RecordId> lookup(Value key) {
    Objects.requireNonNull(key, "key");
    int leaf = findLeaf(key);
    List<Value> keys = nodeKeys.get(leaf);
    int idx = entryIndex(keys, key);
    if (idx < keys.size() && compareValues(keys.get(idx), key) == 0) {
      return List.copyOf(nodeRids.get(leaf).get(idx));
    }
    return List.of();
  }

  @Override
  public synchronized List<RecordId> range(IndexPredicate predicate) {
    Objects.requireNonNull(predicate, "predicate");
    Value bound = Objects.requireNonNull(predicate.value(), "predicate.value()");
    ComparisonOp op = predicate.operator();
    List<RecordId> result = new ArrayList<>();
    switch (op) {
      case EQ -> result.addAll(lookup(bound));
      case LT -> scanUp(bound, false, result);
      case LTE -> scanUp(bound, true, result);
      case GT -> scanFrom(bound, false, result);
      case GTE -> scanFrom(bound, true, result);
      default ->
          throw new DbmsException(
              ErrorCode.UNSUPPORTED_OPERATION, "Operator index tidak didukung: " + op);
    }
    return List.copyOf(result);
  }

  // scan untuk < dan <=
  private void scanUp(Value bound, boolean inclusive, List<RecordId> out) {
    int leaf = leftmostLeaf();
    while (leaf != NO_SIBLING) {
      List<Value> keys = nodeKeys.get(leaf);
      List<List<RecordId>> rids = nodeRids.get(leaf);
      for (int i = 0; i < keys.size(); i++) {
        int cmp = compareValues(keys.get(i), bound);
        boolean include = inclusive ? cmp <= 0 : cmp < 0;
        if (!include) {
          return; // leaf terurut: sisa key pasti di luar rentang
        }
        out.addAll(rids.get(i));
      }
      leaf = nextSibling.get(leaf);
    }
  }

  //scan untuk > dan >=
  private void scanFrom(Value bound, boolean inclusive, List<RecordId> out) {
    int leaf = findLeaf(bound);
    int idx = entryIndex(nodeKeys.get(leaf), bound);
    while (leaf != NO_SIBLING) {
      List<Value> keys = nodeKeys.get(leaf);
      List<List<RecordId>> rids = nodeRids.get(leaf);
      while (idx < keys.size()) {
        int cmp = compareValues(keys.get(idx), bound);
        boolean include = inclusive ? cmp >= 0 : cmp > 0;
        if (include) {
          out.addAll(rids.get(idx));
        }
        idx++;
      }
      leaf = nextSibling.get(leaf);
      idx = 0;
    }
  }

  private int leftmostLeaf() {
    int node = root;
    while (!leafFlag.get(node)) {
      node = nodeChildren.get(node).get(0);
    }
    return node;
  }

  @Override
  public synchronized void insert(Value key, RecordId rid) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(rid, "rid");
    insertRecursive(root, key, rid);
    if (splitRightId != NO_SIBLING) { // akar baru
      int oldRoot = root;
      int newRoot = allocateNode(false);
      nodeKeys.get(newRoot).add(splitSeparator);
      nodeChildren.get(newRoot).add(oldRoot);
      nodeChildren.get(newRoot).add(splitRightId);
      root = newRoot;
      splitRightId = NO_SIBLING;
    }
  }

  private void insertRecursive(int node, Value key, RecordId rid) {
    if (leafFlag.get(node)) {
      List<Value> keys = nodeKeys.get(node);
      int idx = entryIndex(keys, key);
      if (idx < keys.size() && compareValues(keys.get(idx), key) == 0) { // key duplikat
        List<RecordId> rids = nodeRids.get(node).get(idx);
        if (!rids.contains(rid)) {
          rids.add(rid);
        }
        splitRightId = NO_SIBLING;
        return;
      }
      keys.add(idx, key);
      List<RecordId> fresh = new ArrayList<>();
      fresh.add(rid);
      nodeRids.get(node).add(idx, fresh);
      if (keys.size() > MAX_KEYS) {
        splitLeaf(node);
      } else {
        splitRightId = NO_SIBLING;
      }
      return;
    }
    int idx = childIndex(node, key);
    insertRecursive(nodeChildren.get(node).get(idx), key, rid);
    if (splitRightId != NO_SIBLING) { // anak baru
      nodeKeys.get(node).add(idx, splitSeparator);
      nodeChildren.get(node).add(idx + 1, splitRightId);
      splitRightId = NO_SIBLING;
      if (nodeKeys.get(node).size() > MAX_KEYS) {
        splitInternal(node);
      }
    }
  }

  private void splitLeaf(int node) {
    List<Value> keys = nodeKeys.get(node);
    List<List<RecordId>> rids = nodeRids.get(node);
    int total = keys.size(); // MAX_KEYS + 1
    int rightCount = total / 2;
    int leftCount = total - rightCount;
    int right = allocateNode(true);
    nodeKeys.get(right).addAll(keys.subList(leftCount, total));
    nodeRids.get(right).addAll(rids.subList(leftCount, total));
    keys.subList(leftCount, total).clear();
    rids.subList(leftCount, total).clear();
    nextSibling.set(right, nextSibling.get(node));
    nextSibling.set(node, right);
    splitSeparator = nodeKeys.get(right).get(0);
    splitRightId = right;
  }

  private void splitInternal(int node) {
    List<Value> keys = nodeKeys.get(node);
    List<Integer> children = nodeChildren.get(node);
    int totalKeys = keys.size(); // MAX_KEYS + 1
    int mid = totalKeys / 2;
    Value promoted = keys.get(mid);
    int right = allocateNode(false);
    nodeKeys.get(right).addAll(keys.subList(mid + 1, totalKeys));
    nodeChildren.get(right).addAll(children.subList(mid + 1, children.size()));
    keys.subList(mid, totalKeys).clear();
    children.subList(mid + 1, children.size()).clear();
    splitSeparator = promoted;
    splitRightId = right;
  }

  @Override
  public synchronized void remove(Value key, RecordId rid) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(rid, "rid");

    // Turuni tree sambil mencatat jalur (parent, posisi anak).
    List<Integer> pathNodes = new ArrayList<>();
    List<Integer> pathIdx = new ArrayList<>();
    int node = root;
    while (!leafFlag.get(node)) {
      int idx = childIndex(node, key);
      pathNodes.add(node);
      pathIdx.add(idx);
      node = nodeChildren.get(node).get(idx);
    }

    List<Value> keys = nodeKeys.get(node);
    int idx = entryIndex(keys, key);
    if (idx >= keys.size() || compareValues(keys.get(idx), key) != 0) {
      return; // key tidak ada di index: remove idempoten
    }
    List<RecordId> rids = nodeRids.get(node).get(idx);
    if (!rids.remove(rid)) {
      return; // RID ini tidak terdaftar untuk key tersebut
    }
    if (!rids.isEmpty()) {
      return; // key masih hidup untuk RID lain; struktur tidak berubah
    }

    // Entri key habis: hapus entri lalu perbaiki underflow ke arah akar.
    keys.remove(idx);
    nodeRids.get(node).remove(idx);

    int current = node;
    while (current != root && nodeKeys.get(current).size() < MIN_KEYS) {
      int parent = pathNodes.remove(pathNodes.size() - 1);
      int childPos = pathIdx.remove(pathIdx.size() - 1);
      rebalance(parent, childPos);
      current = parent;
    }

    // Akar internal tanpa separator menyusut ke satu-satunya anaknya.
    while (!leafFlag.get(root) && nodeKeys.get(root).isEmpty()) {
      int onlyChild = nodeChildren.get(root).get(0);
      freeNode(root);
      root = onlyChild;
    }
  }

  private void rebalance(int parent, int childPos) {
    List<Integer> children = nodeChildren.get(parent);
    if (childPos > 0 && nodeKeys.get(children.get(childPos - 1)).size() > MIN_KEYS) {
      borrowFromLeft(parent, childPos);
      return;
    }
    if (childPos < children.size() - 1
        && nodeKeys.get(children.get(childPos + 1)).size() > MIN_KEYS) {
      borrowFromRight(parent, childPos);
      return;
    }
    if (childPos > 0) {
      merge(parent, childPos - 1); // child dilebur ke sibling kiri
    } else {
      merge(parent, childPos); // sibling kanan dilebur ke child
    }
  }

  private void borrowFromLeft(int parent, int childPos) {
    int child = nodeChildren.get(parent).get(childPos);
    int left = nodeChildren.get(parent).get(childPos - 1);
    if (leafFlag.get(child)) {
      Value moved = nodeKeys.get(left).remove(nodeKeys.get(left).size() - 1);
      List<RecordId> movedRids = nodeRids.get(left).remove(nodeRids.get(left).size() - 1);
      nodeKeys.get(child).add(0, moved);
      nodeRids.get(child).add(0, movedRids);
      // Separator parent mengikuti key terkecil child yang baru.
      nodeKeys.get(parent).set(childPos - 1, moved);
    } else {
      // Rotasi internal: key parent turun ke child, key terakhir sibling kiri naik ke parent.
      nodeKeys.get(child).add(0, nodeKeys.get(parent).get(childPos - 1));
      nodeChildren
          .get(child)
          .add(0, nodeChildren.get(left).remove(nodeChildren.get(left).size() - 1));
      nodeKeys.get(parent).set(childPos - 1, nodeKeys.get(left).remove(nodeKeys.get(left).size() - 1));
    }
  }

  private void borrowFromRight(int parent, int childPos) {
    int child = nodeChildren.get(parent).get(childPos);
    int right = nodeChildren.get(parent).get(childPos + 1);
    if (leafFlag.get(child)) {
      Value moved = nodeKeys.get(right).remove(0);
      List<RecordId> movedRids = nodeRids.get(right).remove(0);
      nodeKeys.get(child).add(moved);
      nodeRids.get(child).add(movedRids);
      // Separator parent mengikuti key terkecil sibling kanan yang baru.
      nodeKeys.get(parent).set(childPos, nodeKeys.get(right).get(0));
    } else {
      // Rotasi internal: key parent turun ke child, key pertama sibling kanan naik ke parent.
      nodeKeys.get(child).add(nodeKeys.get(parent).get(childPos));
      nodeChildren.get(child).add(nodeChildren.get(right).remove(0));
      nodeKeys.get(parent).set(childPos, nodeKeys.get(right).remove(0));
    }
  }

  private void merge(int parent, int leftPos) {
    int left = nodeChildren.get(parent).get(leftPos);
    int right = nodeChildren.get(parent).get(leftPos + 1);
    if (leafFlag.get(left)) {
      nodeKeys.get(left).addAll(nodeKeys.get(right));
      nodeRids.get(left).addAll(nodeRids.get(right));
      nextSibling.set(left, nextSibling.get(right));
    } else {
      nodeKeys.get(left).add(nodeKeys.get(parent).get(leftPos));
      nodeKeys.get(left).addAll(nodeKeys.get(right));
      nodeChildren.get(left).addAll(nodeChildren.get(right));
    }
    nodeKeys.get(parent).remove(leftPos);
    nodeChildren.get(parent).remove(leftPos + 1);
    freeNode(right);
  }

  private int findLeaf(Value key) {
    int node = root;
    while (!leafFlag.get(node)) {
      node = nodeChildren.get(node).get(childIndex(node, key));
    }
    return node;
  }

  private int childIndex(int node, Value key) {
    List<Value> keys = nodeKeys.get(node);
    int lo = 0;
    int hi = keys.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (compareValues(key, keys.get(mid)) < 0) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }

  private static int entryIndex(List<Value> keys, Value key) {
    int lo = 0;
    int hi = keys.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (compareValues(keys.get(mid), key) < 0) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private int allocateNode(boolean leaf) {
    int id;
    if (freeNodes.isEmpty()) {
      id = leafFlag.size();
      leafFlag.add(leaf);
      nodeKeys.add(new ArrayList<>());
      nodeRids.add(new ArrayList<>());
      nodeChildren.add(new ArrayList<>());
      nextSibling.add(NO_SIBLING);
    } else {
      id = freeNodes.remove(freeNodes.size() - 1);
      leafFlag.set(id, leaf);
      nodeKeys.get(id).clear();
      nodeRids.get(id).clear();
      nodeChildren.get(id).clear();
      nextSibling.set(id, NO_SIBLING);
    }
    return id;
  }

  private void freeNode(int id) {
    nodeKeys.get(id).clear();
    nodeRids.get(id).clear();
    nodeChildren.get(id).clear();
    nextSibling.set(id, NO_SIBLING);
    freeNodes.add(id);
  }

  private static int compareValues(Value left, Value right) {
    int byRank = Integer.compare(rankOf(left), rankOf(right));
    if (byRank != 0) {
      return byRank;
    }
    if (left instanceof NullValue) {
      return 0; // NULL == NULL untuk keperluan index
    }
    if (left instanceof BooleanValue leftBool) {
      return Boolean.compare(leftBool.value(), ((BooleanValue) right).value());
    }
    if (left instanceof IntValue leftInt) {
      return Double.compare(leftInt.value(), numericValue(right));
    }
    if (left instanceof FloatValue leftFloat) {
      return Double.compare(leftFloat.value(), numericValue(right));
    }
    return ((TextValue) left).value().compareTo(((TextValue) right).value());
  }

  private static int rankOf(Value value) {
    if (value instanceof NullValue) {
      return 0;
    }
    if (value instanceof BooleanValue) {
      return 1;
    }
    if (value instanceof IntValue || value instanceof FloatValue) {
      return 2;
    }
    return 3; // TextValue
  }

  private static double numericValue(Value value) {
    return value instanceof IntValue intValue ? intValue.value() : ((FloatValue) value).value();
  }
}
