/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.program.database.mem;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import db.DBConstants;
import db.DBHandle;
import ghidra.framework.model.DomainObject;
import ghidra.framework.model.DomainObjectChangeRecord;
import ghidra.framework.store.LockException;
import ghidra.program.database.ManagerDB;
import ghidra.program.database.ProgramDB;
import ghidra.program.database.code.CodeManager;
import ghidra.program.database.map.AddressMapDB;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.Language;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.*;
import ghidra.program.util.ChangeManager;
import ghidra.util.*;
import ghidra.util.datastruct.IntObjectHashtable;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * The database memory map manager.
 */
public class MemoryMapDB implements Memory, ManagerDB, LiveMemoryListener {

	private ProgramDB program;
	private AddressMapDB addrMap;
	private MemoryMapDBAdapter adapter;

	private static final DataConverter BIG_ENDIAN = BigEndianDataConverter.INSTANCE;
	private static final DataConverter LITTLE_ENDIAN = LittleEndianDataConverter.INSTANCE;

	private DataConverter defaultEndian;
	private MemoryBlock[] blocks;// sorted list of blocks
	private IntObjectHashtable<MemoryBlock> blockMap = new IntObjectHashtable<>();// maps id to MemoryBlockDB objects
	private AddressSet addrSet;
	private AddressSet initializedLoadedAddrSet;
	private AddressSet allInitializedAddrSet;
	private MemoryBlock lastBlock;// the last accessed block
	private LiveMemoryHandler liveMemory;

	Lock lock;

	private static Comparator<Object> BLOCK_ADDRESS_COMPARATOR = (o1, o2) -> {
		MemoryBlock block = (MemoryBlock) o1;
		Address addr = (Address) o2;
		return block.getStart().compareTo(addr);
	};

	/**
	 * Constructs a new MemoryMapDB
	 * @param handle the open database handle.
	 * @param addrMap the address map.
	 * @param openMode the open mode for the program.
	 * @param isBigEndian endianess flag
	 * @param lock the program synchronization lock
	 * @param monitor Task monitor for upgrading
	 * @throws IOException if a database io error occurs.
	 * @throws VersionException if the database version is different from the expected version
	 */
	public MemoryMapDB(DBHandle handle, AddressMapDB addrMap, int openMode, boolean isBigEndian,
			Lock lock, TaskMonitor monitor) throws IOException, VersionException {
		this.addrMap = addrMap;
		this.lock = lock;
		adapter = MemoryMapDBAdapter.getAdapter(handle, openMode, this, monitor);
		defaultEndian = isBigEndian ? BIG_ENDIAN : LITTLE_ENDIAN;
		init(false);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getProgram()
	 */
	@Override
	public Program getProgram() {
		return program;
	}

	/**
	 * @see ghidra.program.database.ManagerDB#invalidateCache(boolean)
	 */
	@Override
	public void invalidateCache(boolean all) throws IOException {
		lock.acquire();
		try {
			init(true);
		}
		finally {
			lock.release();
		}
	}

	private void init(boolean reload) throws IOException {
		synchronized (this) {
			if (reload) {
				adapter.refreshMemory();
			}

			// Minimize chance of accessing field variables while in flux
			MemoryBlockDB[] newBlocks = adapter.getMemoryBlocks();
			AddressSet newAddrSet = new AddressSet();
			AddressSet newLoadedInitializedAddrSet = new AddressSet();
			AddressSet newAllInitializedAddrSet = new AddressSet();
			List<MappedMemoryBlock> mappedMemoryBlocks = new LinkedList<>();
			IntObjectHashtable<MemoryBlock> newBlockMap = new IntObjectHashtable<>();
			for (MemoryBlockDB block : newBlocks) {
				newBlockMap.put(block.getID(), block);
				newAddrSet.addRange(block.getStart(), block.getEnd());
				if (block.isInitialized()) {
					newAllInitializedAddrSet.addRange(block.getStart(), block.getEnd());
					if (block.isLoaded()) {
						newLoadedInitializedAddrSet.addRange(block.getStart(), block.getEnd());
					}
				}
				if (block.isMapped() && block instanceof MappedMemoryBlock) {
					mappedMemoryBlocks.add((MappedMemoryBlock) block);
				}
			}

			// Mapped blocks are uninitialized, but ranges of them may map to initialized ranges.
			// Add in these mapped initialized ranges.  Don't forget to keep them up to date as
			// initialized blocks that they map to come and go (this happens automatically because
			// this method gets called on every add/remove).
			newAllInitializedAddrSet.add(
				getMappedIntersection(mappedMemoryBlocks, newAllInitializedAddrSet));
			newLoadedInitializedAddrSet.add(
				getMappedIntersection(mappedMemoryBlocks, newLoadedInitializedAddrSet));

			lastBlock = null;
			blocks = newBlocks;
			addrSet = newAddrSet;
			initializedLoadedAddrSet = newLoadedInitializedAddrSet;
			allInitializedAddrSet = newAllInitializedAddrSet;
			blockMap = newBlockMap;
		}
		if (liveMemory != null) {
			liveMemory.clearCache();
		}
		addrMap.memoryMapChanged(this);
	}

	public void setLanguage(Language newLanguage) {
		defaultEndian = newLanguage.isBigEndian() ? BIG_ENDIAN : LITTLE_ENDIAN;
	}

	/**
	 * Set the program.
	 */
	@Override
	public void setProgram(ProgramDB program) {
		this.program = program;
		try {
			init(true);
		}
		catch (IOException e) {
			dbError(e);
		}
	}

	/**
	 * @see ghidra.program.database.ManagerDB#programReady(int, int, ghidra.util.task.TaskMonitor)
	 */
	@Override
	public void programReady(int openMode, int currentRevision, TaskMonitor monitor)
			throws IOException, CancelledException {
		if (openMode == DBConstants.UPGRADE) {
			// Ensure that the key has been generated for the end address of each block
			// This will allow undefined data to be returned for all address contained
			// within any 32-bit block (see CodeManager handling of AddressMap.INVALID_ADDRESS_KEY).
			for (MemoryBlock block : blocks) {
				addrMap.getKey(block.getEnd(), true);
			}
		}
	}

	void dbError(IOException e) {
		program.dbError(e);
	}

	/**
	 * Returns the address factory for the program.
	 */
	AddressFactory getAddressFactory() {
		return addrMap.getAddressFactory();
	}

	/**
	 * Returns the AddressMap from the program.
	 */
	AddressMapDB getAddressMap() {
		return addrMap;
	}

	@Override
	public AddressSetView getInitializedAddressSet() {
		return getLoadedAndInitializedAddressSet();
	}

	@Override
	public AddressSetView getAllInitializedAddressSet() {
		return allInitializedAddrSet;
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getLoadedAndInitializedAddressSet()
	 */
	@Override
	public AddressSetView getLoadedAndInitializedAddressSet() {
		if (liveMemory != null) {
			return this;//all memory is initialized!
		}
		return initializedLoadedAddrSet;
	}

	void checkMemoryWrite(Address start, int length) throws MemoryAccessException {
		CodeManager codeManager = program.getCodeManager();
		Instruction instr = codeManager.getInstructionContaining(start);
		if (instr != null) {
			throw new MemoryAccessException(
				"Memory change conflicts with instruction at " + instr.getMinAddress());
		}
		if (length > 1) {
			instr = codeManager.getInstructionAfter(start);
			if (instr != null) {
				Address end = start.add(length - 1);
				if (instr.getMinAddress().compareTo(end) <= 0) {
					throw new MemoryAccessException(
						"Memory change conflicts with instruction at " + instr.getMinAddress());
				}
			}
		}
	}

	void checkMemoryWrite(Address addr) throws MemoryAccessException {
		CodeManager codeManager = program.getCodeManager();
		Instruction instr = codeManager.getInstructionContaining(addr);
		if (instr != null) {
			throw new MemoryAccessException(
				"Memory change conflicts with instruction at " + instr.getMinAddress());
		}
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getBlock(ghidra.program.model.address.Address)
	 */
	@Override
	public MemoryBlock getBlock(Address addr) {
		return getBlockDB(addr);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getBlock(java.lang.String)
	 */
	@Override
	public synchronized MemoryBlock getBlock(String blockName) {
		for (MemoryBlock block : blocks) {
			if (block.getName().equals(blockName)) {
				return block;
			}
		}
		return null;
	}

	private synchronized MemoryBlock getBlockDB(Address addr) {
		if (lastBlock != null) {
			if (lastBlock.contains(addr)) {
				return lastBlock;
			}
		}
		MemoryBlock[] tmpBlocks = blocks;
		int index = Arrays.binarySearch(tmpBlocks, addr, BLOCK_ADDRESS_COMPARATOR);
		if (index >= 0) {
			lastBlock = tmpBlocks[index];
			return lastBlock;
		}
		index = -index - 2;
		if (index >= 0) {
			MemoryBlock block = tmpBlocks[index];
			if (block.contains(addr)) {
				lastBlock = block;
				return block;
			}
		}
		return null;
	}

//	MemoryBlockDB getBlock(int id) {
//		lock.acquire();
//		try {
//			return (MemoryBlockDB) blockMap.get(id);
//		}
//		finally {
//			lock.release();
//		}
//	}

	void fireBlockAdded(MemoryBlock newBlock) {
		AddressRange range = new AddressRangeImpl(newBlock.getStart(), newBlock.getEnd());
		program.getTreeManager().addMemoryBlock(newBlock.getName(), range);
		program.setChanged(ChangeManager.DOCR_MEMORY_BLOCK_ADDED, newBlock.getStart(),
			newBlock.getEnd(), null, null);
		program.fireEvent(new DomainObjectChangeRecord(DomainObject.DO_OBJECT_RESTORED));
	}

	void fireBlockSplit() {
		program.fireEvent(new DomainObjectChangeRecord(DomainObject.DO_OBJECT_RESTORED));
	}

	void fireBlockRemoved(Address blockStartAddr) {
		program.setChanged(ChangeManager.DOCR_MEMORY_BLOCK_REMOVED, blockStartAddr, null);
		program.fireEvent(new DomainObjectChangeRecord(DomainObject.DO_OBJECT_RESTORED));
	}

	void fireBlockMoved(MemoryBlockDB block, Address oldStartAddr) {
		program.setChanged(ChangeManager.DOCR_MEMORY_BLOCKS_JOINED, oldStartAddr, block);
		program.fireEvent(new DomainObjectChangeRecord(DomainObject.DO_OBJECT_RESTORED));
	}

	/**
	 * Two blocks have been joined producing newBlock.  The block which was
	 * eliminated can be identified using the oldBlockStartAddr.
	 * @param newBlock
	 * @param oldBlockStartAddr
	 */
	void fireBlocksJoined(MemoryBlock newBlock, Address oldBlockStartAddr) {
		program.setChanged(ChangeManager.DOCR_MEMORY_BLOCKS_JOINED, oldBlockStartAddr, newBlock);
	}

	void fireBlockSplit(MemoryBlockDB originalBlock, MemoryBlockDB newBlock) {
		program.setChanged(ChangeManager.DOCR_MEMORY_BLOCK_SPLIT, null, null, originalBlock,
			newBlock);
	}

	void fireBlockChanged(MemoryBlockDB block) {
		if (program != null) {
			program.setChanged(ChangeManager.DOCR_MEMORY_BLOCK_CHANGED, block, null);
		}
	}

	void fireBytesChanged(Address addr, int count) {
		lock.acquire();
		try {
			Address end = addr.addNoWrap(count - 1);

			program.getCodeManager().memoryChanged(addr, end);
			program.setChanged(ChangeManager.DOCR_MEMORY_BYTES_CHANGED, addr, end, null, null);

		}
		catch (AddressOverflowException e) {
			// shouldn't happen
			throw new AssertException(e.getMessage());
		}
		finally {
			lock.release();
		}
	}

	@Override
	public boolean isBigEndian() {
		return defaultEndian == BIG_ENDIAN;
	}

	@Override
	public void setLiveMemoryHandler(LiveMemoryHandler handler) {
		lock.acquire();
		try {
			if (liveMemory != null) {
				liveMemory.removeLiveMemoryListener(this);
			}
			liveMemory = handler;
			if (liveMemory != null) {
				liveMemory.addLiveMemoryListener(this);
			}
			program.invalidate();
		}
		finally {
			lock.release();
		}
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getLiveMemoryHandler()
	 */
	@Override
	public LiveMemoryHandler getLiveMemoryHandler() {
		return liveMemory;
	}

	@Override
	public MemoryBlock createInitializedBlock(String name, Address start, long size,
			byte initialValue, TaskMonitor monitor, boolean overlay)
			throws LockException, MemoryConflictException, AddressOverflowException,
			CancelledException, DuplicateNameException {

		InputStream fillStream = null;
		if (initialValue != 0) {
			final int fillByte = initialValue & 0xff;
			fillStream = new InputStream() {
				@Override
				public int read() throws IOException {
					return fillByte;
				}
			};
		}
		return createInitializedBlock(name, start, fillStream, size, monitor, overlay);
	}

	private Address createOverlaySpace(String name, Address start, long dataLength)
			throws MemoryConflictException, AddressOverflowException, DuplicateNameException,
			LockException {

		AddressSpace space = start.getAddressSpace();
		if (space.isOverlaySpace()) {
			throw new IllegalArgumentException("An overlay block may not be overlayed");
		}
		if (!space.isMemorySpace()) {
			throw new IllegalArgumentException(
				"Invalid physical address for overlay block: " + start.toString(true));
		}

		start.addNoWrap(dataLength - 1);// just tests the AddressOverflow condition.

		AddressSpace ovSpace = program.addOverlaySpace(name, start.getAddressSpace(),
			start.getOffset(), start.getOffset() + (dataLength - 1));

		Address ovAddr = ovSpace.getAddress(start.getOffset());
		return ovAddr;
	}

	@Override
	public MemoryBlock createInitializedBlock(String name, Address start, InputStream is,
			long length, TaskMonitor monitor, boolean overlay) throws MemoryConflictException,
			AddressOverflowException, CancelledException, LockException, DuplicateNameException {

		lock.acquire();
		try {
			checkBlockSize(length, true);

			program.checkExclusiveAccess();
			if (monitor != null && is != null) {
				is = new MonitoredInputStream(is, monitor);
			}
			if (overlay) {
				start = createOverlaySpace(name, start, length);
			}
			else {
				checkRange(start, length);
			}
			try {
				adapter.createInitializedBlock(name, start, is, length, MemoryBlock.READ);
				init(true);
				MemoryBlock newBlock = getBlockDB(start);
				fireBlockAdded(newBlock);
				return newBlock;
			}
			catch (IOCancelledException e) {
				throw new CancelledException();
			}
			catch (IOException e) {
				program.dbError(e);
			}

			return null;
		}
		finally {
			lock.release();
		}

	}

	@Override
	public MemoryBlock createUninitializedBlock(String name, Address start, long size,
			boolean overlay) throws MemoryConflictException, AddressOverflowException,
			LockException, DuplicateNameException {

		lock.acquire();
		try {
			checkBlockSize(size, false);

			program.checkExclusiveAccess();

			if (overlay) {
				start = createOverlaySpace(name, start, size);
			}
			else {
				checkRange(start, size);
			}
			try {
				adapter.createBlock(MemoryBlockType.DEFAULT, name, start, size, null, false,
					MemoryBlock.READ);
				init(true);
				MemoryBlock newBlock = getBlockDB(start);
				fireBlockAdded(newBlock);
				return newBlock;
			}
			catch (IOException e) {
				program.dbError(e);
			}
		}
		finally {
			lock.release();
		}
		return null;
	}

	@Override
	public MemoryBlock createBitMappedBlock(String name, Address start, Address overlayAddress,
			long length) throws MemoryConflictException, AddressOverflowException, LockException {
		lock.acquire();
		try {
			checkBlockSize(length, false);
			program.checkExclusiveAccess();
			checkRange(start, length);
			overlayAddress.addNoWrap((length - 1) / 8);// just to check if length fits in address space
			try {
				adapter.createBlock(MemoryBlockType.BIT_MAPPED, name, start, length, overlayAddress,
					false, MemoryBlock.READ);
				init(true);
				MemoryBlock newBlock = getBlockDB(start);
				fireBlockAdded(newBlock);
				return newBlock;
			}
			catch (IOException e) {
				program.dbError(e);
			}
		}
		finally {
			lock.release();
		}
		return null;
	}

	@Override
	public MemoryBlock createByteMappedBlock(String name, Address start, Address overlayAddress,
			long length) throws MemoryConflictException, AddressOverflowException, LockException {
		lock.acquire();
		try {
			checkBlockSize(length, false);
			program.checkExclusiveAccess();
			checkRange(start, length);
			overlayAddress.addNoWrap(length - 1);// just to check if length fits in address space
			try {
				adapter.createBlock(MemoryBlockType.BYTE_MAPPED, name, start, length,
					overlayAddress, false, MemoryBlock.READ);
				init(true);
				MemoryBlock newBlock = getBlockDB(start);
				fireBlockAdded(newBlock);
				return newBlock;
			}
			catch (IOException e) {
				program.dbError(e);
			}
		}
		finally {
			lock.release();
		}
		return null;
	}

	@Override
	public MemoryBlock createBlock(MemoryBlock block, String name, Address start, long length)
			throws MemoryConflictException, AddressOverflowException, LockException {
		lock.acquire();
		try {
			checkBlockSize(length, block.isInitialized());
			program.checkExclusiveAccess();
			checkRange(start, length);

			try {
				Address overlayAddr = null;
				if (block instanceof MappedMemoryBlock) {
					overlayAddr = ((MappedMemoryBlock) block).getOverlayedMinAddress();
				}
				adapter.createBlock(block.getType(), name, start, length, overlayAddr,
					block.isInitialized(), block.getPermissions());
				init(true);
				MemoryBlock newBlock = getBlockDB(start);
				fireBlockAdded(newBlock);
				return newBlock;
			}
			catch (IOException e) {
				program.dbError(e);
			}
			return null;

		}
		finally {
			lock.release();
		}
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getSize()
	 */
	@Override
	public long getSize() {
		return addrSet.getNumAddresses();
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getBlocks()
	 */
	@Override
	public MemoryBlock[] getBlocks() {
		lock.acquire();
		try {
			return blocks.clone();
		}
		finally {
			lock.release();
		}
	}

	@Override
	public void moveBlock(MemoryBlock block, Address newStartAddr, TaskMonitor monitor)
			throws MemoryBlockException, MemoryConflictException, AddressOverflowException,
			NotFoundException, LockException {
		lock.acquire();
		try {
			program.checkExclusiveAccess();
			if (liveMemory != null) {
				throw new MemoryBlockException(
					"Memory move operation not permitted while live memory is active");
			}
			Address oldStartAddr = block.getStart();
			if (!(block instanceof MemoryBlockDB)) {
				throw new NotFoundException("Block does not belong to this program");
			}
			MemoryBlockDB memBlock = (MemoryBlockDB) block;
			if (memBlock.memMap != this) {
				throw new NotFoundException("Block does not belong to this program");
			}
			if (memBlock.getType() == MemoryBlockType.OVERLAY) {
				throw new IllegalArgumentException("Overlay blocks cannot be moved");
			}
			if (newStartAddr.getAddressSpace().isOverlaySpace()) {
				throw new IllegalArgumentException("Can not move a block into an overlay space.");
			}

			program.setEventsEnabled(false);// ensure that no domain object change
			// events go out that would cause screen updates;
			// the code manager will be locked until the remove is done
			try {
				memBlock.checkValid();
				Address newEndAddr = newStartAddr.addNoWrap(block.getSize() - 1);
				AddressSet set = new AddressSet(addrSet);
				set.deleteRange(block.getStart(), block.getEnd());
				if (set.intersects(newStartAddr, newEndAddr)) {
					throw new MemoryConflictException(
						"Block move conflicts with other existing memory block");
				}
				try {
					memBlock.setStartAddress(newStartAddr);
					init(false);
				}
				catch (IOException e) {
					program.dbError(e);
				}
				program.moveAddressRange(oldStartAddr, newStartAddr, memBlock.length, monitor);
			}
			finally {
				program.invalidate();
				program.setEventsEnabled(true);
			}
			fireBlockMoved(memBlock, oldStartAddr);

		}
		finally {
			lock.release();
		}
	}

	@Override
	public void split(MemoryBlock block, Address addr)
			throws MemoryBlockException, NotFoundException, LockException {
		lock.acquire();
		try {
			program.checkExclusiveAccess();
			if (liveMemory != null) {
				throw new MemoryBlockException(
					"Memory split operation not permitted while live memory is active");
			}
			if (!(block instanceof MemoryBlockDB)) {
				throw new NotFoundException("Block does not belong to this program");
			}
			MemoryBlockDB memBlock = (MemoryBlockDB) block;
			if (!memBlock.contains(addr)) {
				throw new IllegalArgumentException("Block must contain split address");
			}
			if (addr.equals(memBlock.getStart())) {
				throw new IllegalArgumentException("Split cannot be done on block start address");
			}
			if (memBlock.getType() == MemoryBlockType.OVERLAY) {
				throw new IllegalArgumentException("Split cannot be done on an overlay block");
			}
			if (memBlock.getType() == MemoryBlockType.BIT_MAPPED) {
				throw new IllegalArgumentException("Split cannot be done on a bit mapped block");
			}
			try {
				memBlock.split(addr);
				init(true);
				fireBlockSplit();
			}
			catch (IOException e) {
				program.dbError(e);
			}

		}
		finally {
			lock.release();
		}

	}

	@Override
	public MemoryBlock join(MemoryBlock blockOne, MemoryBlock blockTwo)
			throws MemoryBlockException, NotFoundException, LockException {
		lock.acquire();
		try {
			program.checkExclusiveAccess();
			if (liveMemory != null) {
				throw new MemoryBlockException(
					"Memory join operation not permitted while live memory is active");
			}
			if (!(blockOne instanceof MemoryBlockDB) || !(blockTwo instanceof MemoryBlockDB)) {
				throw new NotFoundException("Blocks do not belong to this program");
			}
			if (blockOne.getType() != blockTwo.getType()) {
				throw new MemoryBlockException("Blocks of different types can not be joined");
			}
			if (blockOne.isInitialized() != blockTwo.isInitialized()) {
				throw new MemoryBlockException(
					"Both blocks must be either initialized or uninitialized");
			}
			if (blockOne.getType() == MemoryBlockType.OVERLAY) {
				throw new IllegalArgumentException("Cannot join overlay blocks");
			}
			if (blockOne.getType() == MemoryBlockType.BIT_MAPPED) {
				throw new IllegalArgumentException("Cannot join bit mapped blocks");
			}
			if (blockOne.getType() == MemoryBlockType.BYTE_MAPPED) {
				throw new IllegalArgumentException("Cannot join byte mapped blocks");
			}
			long size1 = blockOne.getSize();
			long size2 = blockTwo.getSize();
			if (size1 + size2 > Integer.MAX_VALUE) {
				throw new MemoryBlockException("Blocks are too large to be joined");
			}
			if (blockOne.getStart().compareTo(blockTwo.getStart()) > 0) {
				MemoryBlock tmp = blockOne;
				blockOne = blockTwo;
				blockTwo = tmp;
			}
			MemoryBlockDB memBlock1 = (MemoryBlockDB) blockOne;
			MemoryBlockDB memBlock2 = (MemoryBlockDB) blockTwo;
			Address block1Addr = blockOne.getStart();
			Address block2Addr = blockTwo.getStart();
			memBlock1.checkValid();
			memBlock2.checkValid();

			if (memBlock1.memMap != this || memBlock2.memMap != this) {
				throw new NotFoundException("Blocks do not belong to this program");
			}
			if (!(memBlock1.getEnd().isSuccessor(memBlock2.getStart()))) {
				throw new MemoryBlockException("Blocks are not contiguous");
			}
			MemoryBlock newBlock = null;
			try {
				memBlock1.join(memBlock2);
				init(true);
				newBlock = getBlockDB(block1Addr);
				fireBlocksJoined(newBlock, block2Addr);

			}
			catch (IOException e) {
				program.dbError(e);
			}
			return newBlock;
		}
		finally {
			lock.release();
		}

	}

	@Override
	public MemoryBlock convertToInitialized(MemoryBlock unitializedBlock, byte initialValue)
			throws MemoryBlockException, NotFoundException, LockException {
		lock.acquire();
		try {
			program.checkExclusiveAccess();
			if (!(unitializedBlock instanceof MemoryBlockDB)) {
				throw new NotFoundException("Block does not belong to this program");
			}
			if (unitializedBlock.isInitialized()) {
				throw new IllegalArgumentException(
					"Only an Uninitialized Block may be converted to an Initialized Block");
			}
			MemoryBlockType type = unitializedBlock.getType();
			if (!((type == MemoryBlockType.DEFAULT) || (type == MemoryBlockType.OVERLAY))) {
				throw new IllegalArgumentException("Block is of a type that cannot be initialized");
			}
			long size = unitializedBlock.getSize();
			if (size > MAX_INITIALIZED_BLOCK_SIZE) {
				throw new MemoryBlockException("Block too large to initialize");
			}
			MemoryBlockDB memBlock = (MemoryBlockDB) unitializedBlock;
			try {
				memBlock.initializeBlock(initialValue);
				allInitializedAddrSet.addRange(memBlock.getStart(), memBlock.getEnd());
				initializedLoadedAddrSet.addRange(memBlock.getStart(), memBlock.getEnd());
				fireBlockChanged(memBlock);
				fireBytesChanged(memBlock.getStart(), (int) memBlock.getSize());
				return memBlock;
			}
			catch (IOException e) {
				program.dbError(e);
			}
			return null;

		}
		finally {
			lock.release();
		}

	}

	@Override
	public MemoryBlock convertToUninitialized(MemoryBlock initializedBlock)
			throws MemoryBlockException, NotFoundException, LockException {
		lock.acquire();
		try {
			program.checkExclusiveAccess();
			if (!(initializedBlock instanceof MemoryBlockDB)) {
				throw new NotFoundException("Block does not belong to this program");
			}
			if (!initializedBlock.isInitialized()) {
				throw new IllegalArgumentException(
					"Only an Initialized Block may be converted to an Uninitialized Block");
			}
			MemoryBlockType type = initializedBlock.getType();
			if (!((type == MemoryBlockType.DEFAULT) || (type == MemoryBlockType.OVERLAY))) {
				throw new IllegalArgumentException(
					"Block is of a type that cannot be uninitialized");
			}
			MemoryBlockDB memBlock = (MemoryBlockDB) initializedBlock;
			try {
				memBlock.uninitializeBlock();
				allInitializedAddrSet.deleteRange(memBlock.getStart(), memBlock.getEnd());
				initializedLoadedAddrSet.deleteRange(memBlock.getStart(), memBlock.getEnd());
				fireBlockChanged(memBlock);
				fireBytesChanged(memBlock.getStart(), (int) memBlock.getSize());
				return memBlock;
			}
			catch (IOException e) {
				program.dbError(e);
			}
			return null;

		}
		finally {
			lock.release();
		}

	}

	@Override
	public Address findBytes(Address addr, byte[] bytes, byte[] masks, boolean forward,
			TaskMonitor monitor) {
		if (monitor == null) {
			monitor = TaskMonitorAdapter.DUMMY_MONITOR;
		}

		AddressIterator it = initializedLoadedAddrSet.getAddresses(addr, forward);
		byte[] b = new byte[bytes.length];
		if (forward) {
			while (it.hasNext() && !monitor.isCancelled()) {
				Address addr2 = it.next();
				int moffset = match(addr2, bytes, masks, b, forward);
				if (moffset < 0) {
					try {
						Address jumpAddr = addr2.addNoWrap(-moffset);
						if (jumpAddr.hasSameAddressSpace(addr2)) {
							it = initializedLoadedAddrSet.getAddresses(jumpAddr, forward);
						}
						monitor.incrementProgress(-moffset);
					}
					catch (AddressOverflowException e) {
					}
					continue;
				}
				if (moffset == 1) {
					return addr2;
				}

				monitor.incrementProgress(moffset);
			}
		}
		else {
			while (it.hasNext() && !monitor.isCancelled()) {
				Address addr2 = it.next();
				int moffset = match(addr2, bytes, masks, b, forward);
				if (moffset == 1) {
					return addr2;
				}

				monitor.incrementProgress(moffset);
			}
		}
		return null;
	}

	@Override
	public Address findBytes(Address startAddr, Address endAddr, byte[] bytes, byte[] masks,
			boolean forward, TaskMonitor monitor) {
		if (monitor == null) {
			monitor = TaskMonitorAdapter.DUMMY_MONITOR;
		}
		AddressIterator it = allInitializedAddrSet.getAddresses(startAddr, forward);
		byte[] b = new byte[bytes.length];
		if (forward) {
			while (it.hasNext() && !monitor.isCancelled()) {
				Address addr2 = it.next();
				if (addr2.compareTo(endAddr) > 0) {
					return null;
				}
				int moffset = match(addr2, bytes, masks, b, forward);
				if (moffset < 0) {
					try {
						Address jumpAddr = addr2.addNoWrap(-moffset);
						if (jumpAddr.hasSameAddressSpace(addr2)) {
							it = allInitializedAddrSet.getAddresses(jumpAddr, forward);
						}
						monitor.incrementProgress(-moffset);
					}
					catch (AddressOverflowException e) {
						moffset = -moffset;
						for (int i = 0; i < moffset; i++) {
							if (it.hasNext()) {
								it.next();
							}
							else {
								break;
							}
						}
						monitor.incrementProgress(moffset);
					}
					continue;
				}
				if (moffset == 1) {
					return addr2;
				}

				// No match, and we're going to move to the next address so increment our
				// progress by 1.
				monitor.incrementProgress(1);
			}
		}
		else {
			while (it.hasNext() && !monitor.isCancelled()) {
				Address addr2 = it.next();
				if (addr2.compareTo(endAddr) < 0) {
					return null;
				}
				int moffset = match(addr2, bytes, masks, b, forward);
				if (moffset == 1) {
					return addr2;
				}

				// If we're here, then no match was found so just increment to the monitor
				// by 1 (one address).
				monitor.incrementProgress(1);
			}
		}
		return null;
	}

	/**
	 * Tests if the memory contains a sequence of contiguous bytes that match the
	 * given byte array at all bit positions where the mask contains an "on" bit.
	 * The test will be something like
	 *
	 * for(int i=0;i<bytes.length;i++) {
	 *     if (bytes[i] != memory.getByte(addr+i) & masks[i]) {
	 *         return false;
	 *     }
	 * }
	 * return false;
	 *
	 * @param addr The beginning address in memory to test against.
	 * @param bytes the array of bytes to test for.
	 * @param masks the array of masks. (One for each byte in the byte array)
	 * @param forward if true, the matching is going forward, otherwise backward
	 *
	 * @return 1 if there is a match
	 *         0 if there is no match
	 *        -i if no match is found, this is the number of bytes that can be safely skipped
	 */
	private int match(Address addr, byte[] bytes, byte[] masks, byte[] data, boolean forward) {
		try {
			if (getBytes(addr, data) < data.length) {
				return 0;
			}

			//
			// if there is no mask, check is simpler
			//
			if (masks == null) {
				// check if the bytes pattern entirely matches the data
				if (Arrays.equals(data, bytes)) {
					return 1;
				}

				if (!forward) {
					return 0;
				}

				// check to see if the first byte of the pattern
				// matches any byte in the buffer
				//  if it does, return it's negative offset
				for (int j = 1; j < bytes.length; j++) {
					int off = 0;
					for (; off < (data.length - j); off++) {
						if (bytes[off] != data[j + off]) {
							break;
						}
					}
					if (off + j == data.length) {
						return -j;
					}
				}
				return -bytes.length;
			}

			// first check if the pattern entirely matches the bytes
			int i;
			for (i = 0; i < bytes.length; i++) {
				if ((data[i] & masks[i]) != (bytes[i] & masks[i])) {
					break;
				}
			}
			if (i == bytes.length) {
				return 1;
			}

			if (!forward) {
				return 0;
			}

			// check to see if the first byte of the pattern
			// matches any byte in the buffer
			//  if it does, return it's negative offset
			for (int j = 1; j < bytes.length; j++) {
				int off = 0;
				for (; off < (data.length - j); off++) {
					if ((bytes[off] & masks[off]) != (data[j + off] & masks[off])) {
						break;
					}
				}
				if (off + j == data.length) {
					return -j;
				}
			}
			return -bytes.length;
		}
		catch (Exception e) {
			return 0;
		}
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getByte(ghidra.program.model.address.Address)
	 */
	@Override
	public byte getByte(Address addr) throws MemoryAccessException {
		lock.acquire();
		try {
			if (liveMemory != null) {
				return liveMemory.getByte(addr);
			}
			MemoryBlock block = getBlockDB(addr);
			if (block == null) {
				throw new MemoryAccessException(
					"Address " + addr.toString(true) + " does not exist in memory");
			}
			return block.getByte(addr);
		}
		finally {
			lock.release();
		}

	}

	@Override
	public int getBytes(Address addr, byte[] dest) throws MemoryAccessException {
		return getBytes(addr, dest, 0, dest.length);
	}

	@Override
	public int getBytes(Address addr, byte[] dest, int dIndex, int size)
			throws MemoryAccessException {
		if (liveMemory != null) {
			return liveMemory.getBytes(addr, dest, dIndex, size);
		}
		int numRead = 0;
		long lastRead = 0;
		while (numRead < size) {
			try {
				addr = addr.addNoWrap(lastRead);
				MemoryBlock block = getBlock(addr);
				if (block == null) {
					break;
				}
				if (block.isInitialized() || block.isMapped()) {
					lastRead = block.getBytes(addr, dest, numRead + dIndex, size - numRead);
				}
				else {
					break;
				}
				numRead += lastRead;
			}
			catch (AddressOverflowException e) {
				break;
			}
		}
		if (numRead == 0 && size > 0) {
			throw new MemoryAccessException("Unable to read bytes at " + addr.toString(true));
		}
		return numRead;
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getShort(ghidra.program.model.address.Address)
	 */
	@Override
	public short getShort(Address addr) throws MemoryAccessException {
		byte[] byteBuf = new byte[2];
		int n = getBytes(addr, byteBuf, 0, 2);
		if (n != 2) {
			throw new MemoryAccessException("Could not get short at " + addr.toString(true));
		}
		return defaultEndian.getShort(byteBuf);
	}

	@Override
	public short getShort(Address addr, boolean isBigEndian) throws MemoryAccessException {
		byte[] byteBuf = new byte[2];
		int n = getBytes(addr, byteBuf, 0, 2);
		if (n != 2) {
			throw new MemoryAccessException("Could not get short at " + addr.toString(true));
		}
		if (isBigEndian) {
			return BIG_ENDIAN.getShort(byteBuf);
		}
		return LITTLE_ENDIAN.getShort(byteBuf);
	}

	@Override
	public int getShorts(Address addr, short[] dest) throws MemoryAccessException {
		return getShorts(addr, dest, 0, dest.length);
	}

	@Override
	public int getShorts(Address addr, short[] dest, int dIndex, int nElem)
			throws MemoryAccessException {
		byte[] byteBuf = new byte[2 * nElem];
		int n = getBytes(addr, byteBuf, 0, byteBuf.length);
		if (n < 2) {
			throw new MemoryAccessException("Could not read shorts at " + addr.toString(true));
		}
		// round down
		n = n / 2;
		for (int i = 0; i < 2 * n; i += 2) {
			dest[dIndex + i / 2] = defaultEndian.getShort(byteBuf, i);
		}
		return n;
	}

	@Override
	public int getShorts(Address addr, short[] dest, int dIndex, int nElem, boolean isBigEndian)
			throws MemoryAccessException {
		byte[] byteBuf = new byte[2 * nElem];
		int n = getBytes(addr, byteBuf, 0, byteBuf.length);
		if (n < 2) {
			throw new MemoryAccessException("Could not read shorts at " + addr.toString(true));
		}
		// round down
		n = n / 2;
		if (isBigEndian) {
			for (int i = 0; i < 2 * n; i += 2) {
				dest[dIndex + i / 2] = BIG_ENDIAN.getShort(byteBuf, i);
			}
		}
		else {
			for (int i = 0; i < 2 * n; i += 2) {
				dest[dIndex + i / 2] = LITTLE_ENDIAN.getShort(byteBuf, i);
			}
		}
		return n;
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getInt(ghidra.program.model.address.Address)
	 */
	@Override
	public int getInt(Address addr) throws MemoryAccessException {
		byte[] byteBuf = new byte[4];
		int n = getBytes(addr, byteBuf, 0, 4);
		if (n != 4) {
			throw new MemoryAccessException("Could not get int at " + addr.toString(true));
		}
		return defaultEndian.getInt(byteBuf);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getInt(ghidra.program.model.address.Address, boolean)
	 */
	@Override
	public int getInt(Address addr, boolean isBigEndian) throws MemoryAccessException {
		byte[] byteBuf = new byte[4];
		int n = getBytes(addr, byteBuf, 0, 4);
		if (n != 4) {
			throw new MemoryAccessException("Could not get int at " + addr.toString(true));
		}
		if (isBigEndian) {
			return BIG_ENDIAN.getInt(byteBuf);
		}
		return LITTLE_ENDIAN.getInt(byteBuf);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getInts(ghidra.program.model.address.Address, int[])
	 */
	@Override
	public int getInts(Address addr, int[] dest) throws MemoryAccessException {
		return getInts(addr, dest, 0, dest.length);
	}

	@Override
	public int getInts(Address addr, int[] dest, int dIndex, int nElem)
			throws MemoryAccessException {
		byte[] byteBuf = new byte[4 * nElem];
		int n = getBytes(addr, byteBuf, 0, byteBuf.length);
		if (n < 4) {
			throw new MemoryAccessException("Could not read ints at " + addr.toString(true));
		}
		// round down
		n = n / 4;
		for (int i = 0; i < 4 * n; i += 4) {
			dest[dIndex + i / 4] = defaultEndian.getInt(byteBuf, i);
		}
		return n;
	}

	@Override
	public int getInts(Address addr, int[] dest, int dIndex, int nElem, boolean isBigEndian)
			throws MemoryAccessException {
		byte[] byteBuf = new byte[4 * nElem];
		int n = getBytes(addr, byteBuf, 0, byteBuf.length);
		if (n < 4) {
			throw new MemoryAccessException("Could not read ints at " + addr.toString(true));
		}
		// round down
		n = n / 4;
		if (isBigEndian) {
			for (int i = 0; i < 4 * n; i += 4) {
				dest[dIndex + i / 4] = BIG_ENDIAN.getInt(byteBuf, i);
			}
		}
		else {
			for (int i = 0; i < 4 * n; i += 4) {
				dest[dIndex + i / 4] = LITTLE_ENDIAN.getInt(byteBuf, i);
			}
		}
		return n;
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getLong(ghidra.program.model.address.Address)
	 */
	@Override
	public long getLong(Address addr) throws MemoryAccessException {
		byte[] byteBuf = new byte[8];
		int n = getBytes(addr, byteBuf, 0, 8);
		if (n != 8) {
			throw new MemoryAccessException("Could not get long at " + addr.toString(true));
		}
		return defaultEndian.getLong(byteBuf);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getLong(ghidra.program.model.address.Address, boolean)
	 */
	@Override
	public long getLong(Address addr, boolean isBigEndian) throws MemoryAccessException {
		byte[] byteBuf = new byte[8];
		int n = getBytes(addr, byteBuf, 0, 8);
		if (n != 8) {
			throw new MemoryAccessException("Could not get long at " + addr.toString(true));
		}
		if (isBigEndian) {
			return BIG_ENDIAN.getLong(byteBuf);
		}
		return LITTLE_ENDIAN.getLong(byteBuf);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#getLongs(ghidra.program.model.address.Address, long[])
	 */
	@Override
	public int getLongs(Address addr, long[] dest) throws MemoryAccessException {
		return getLongs(addr, dest, 0, dest.length);
	}

	@Override
	public int getLongs(Address addr, long[] dest, int dIndex, int nElem)
			throws MemoryAccessException {
		byte[] byteBuf = new byte[8 * nElem];
		int n = getBytes(addr, byteBuf, 0, byteBuf.length);
		if (n < 8) {
			throw new MemoryAccessException("Could not read longs at " + addr.toString(true));
		}
		// round down
		n = n / 8;
		for (int i = 0; i < 8 * n; i += 8) {
			dest[dIndex + i / 8] = defaultEndian.getLong(byteBuf, i);
		}
		return n;
	}

	@Override
	public int getLongs(Address addr, long[] dest, int dIndex, int nElem, boolean isBigEndian)
			throws MemoryAccessException {
		byte[] byteBuf = new byte[8 * nElem];
		int n = getBytes(addr, byteBuf, 0, byteBuf.length);
		if (n < 8) {
			throw new MemoryAccessException("Could not read longs at " + addr.toString(true));
		}
		// round down
		n = n / 8;
		if (isBigEndian) {
			for (int i = 0; i < 8 * n; i += 8) {
				dest[dIndex + i / 8] = BIG_ENDIAN.getLong(byteBuf, i);
			}
		}
		else {
			for (int i = 0; i < 8 * n; i += 8) {
				dest[dIndex + i / 8] = LITTLE_ENDIAN.getLong(byteBuf, i);
			}
		}
		return n;
	}

	/**
	 * @see ghidra.program.model.mem.Memory#setByte(ghidra.program.model.address.Address, byte)
	 */
	@Override
	public void setByte(Address addr, byte value) throws MemoryAccessException {
		if (liveMemory != null) {
			liveMemory.putByte(addr, value);
			fireBytesChanged(addr, 1);
			return;
		}
		lock.acquire();
		try {
			MemoryBlock block = getBlock(addr);
			if (block == null) {
				throw new MemoryAccessException(
					"Address " + addr.toString(true) + " does not exist in memory");
			}
			block.putByte(addr, value);

		}
		finally {
			lock.release();
		}

	}

	/**
	 * @see ghidra.program.model.mem.Memory#setBytes(ghidra.program.model.address.Address, byte[])
	 */
	@Override
	public void setBytes(Address addr, byte[] source) throws MemoryAccessException {
		setBytes(addr, source, 0, source.length);
	}

	@Override
	public void setBytes(Address address, byte[] source, int sIndex, int size)
			throws MemoryAccessException {
		if (liveMemory != null) {
			int cnt = liveMemory.putBytes(address, source, sIndex, size);
			fireBytesChanged(address, cnt);
			return;
		}

		lock.acquire();
		try {
			Address addr = address;
			int n = size;
			// loop first just to make sure the operation can complete before making any changes
			while (n > 0) {
				MemoryBlock block = getBlock(addr);
				if (block == null) {
					throw new MemoryAccessException(
						"Address " + addr.toString(true) + " does not exist in memory");
				}
				n -= block.getSize() - addr.subtract(block.getStart());
				if (n <= 0) {
					break;
				}
				try {
					addr = block.getEnd().addNoWrap(1);
				}
				catch (AddressOverflowException e) {
					throw new MemoryAccessException("Attempted to write beyond address space");
				}
			}

			addr = address;
			n = size;
			int offset = sIndex;
			while (n > 0) {
				MemoryBlock block = getBlock(addr);
				int cnt = block.putBytes(addr, source, offset, n);
				offset += cnt;
				n -= cnt;
				if (n <= 0) {
					break;
				}
				addr = block.getEnd().add(1);
			}

		}
		finally {
			lock.release();
		}

	}

	/**
	 * @see ghidra.program.model.mem.Memory#setShort(ghidra.program.model.address.Address, short)
	 */
	@Override
	public void setShort(Address addr, short value) throws MemoryAccessException {
		byte[] byteBuf = new byte[2];
		defaultEndian.getBytes(value, byteBuf);
		setBytes(addr, byteBuf, 0, 2);
	}

	@Override
	public void setShort(Address addr, short value, boolean isBigEndian)
			throws MemoryAccessException {
		byte[] byteBuf = new byte[2];
		if (isBigEndian) {
			BIG_ENDIAN.getBytes(value, byteBuf);
		}
		else {
			LITTLE_ENDIAN.getBytes(value, byteBuf);
		}
		setBytes(addr, byteBuf, 0, 2);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#setInt(ghidra.program.model.address.Address, int)
	 */
	@Override
	public void setInt(Address addr, int value) throws MemoryAccessException {
		byte[] byteBuf = new byte[4];
		defaultEndian.getBytes(value, byteBuf);
		setBytes(addr, byteBuf, 0, 4);
	}

	@Override
	public void setInt(Address addr, int value, boolean isBigEndian) throws MemoryAccessException {
		byte[] byteBuf = new byte[4];
		if (isBigEndian) {
			BIG_ENDIAN.getBytes(value, byteBuf);
		}
		else {
			LITTLE_ENDIAN.getBytes(value, byteBuf);
		}
		setBytes(addr, byteBuf, 0, 4);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#setLong(ghidra.program.model.address.Address, long)
	 */
	@Override
	public void setLong(Address addr, long value) throws MemoryAccessException {
		byte[] byteBuf = new byte[8];
		defaultEndian.getBytes(value, byteBuf);
		setBytes(addr, byteBuf, 0, 8);
	}

	/**
	 * @see ghidra.program.model.mem.Memory#setLong(ghidra.program.model.address.Address, long, boolean)
	 */
	@Override
	public void setLong(Address addr, long value, boolean isBigEndian)
			throws MemoryAccessException {
		byte[] byteBuf = new byte[8];
		if (isBigEndian) {
			BIG_ENDIAN.getBytes(value, byteBuf);
		}
		else {
			LITTLE_ENDIAN.getBytes(value, byteBuf);
		}
		setBytes(addr, byteBuf, 0, 8);
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#contains(ghidra.program.model.address.Address)
	 */
	@Override
	public boolean contains(Address addr) {
		return addrSet.contains(addr);
	}

	@Override
	public boolean contains(Address start, Address end) {
		return addrSet.contains(start, end);
	}

	@Override
	public boolean contains(AddressSetView set) {
		return addrSet.contains(set);
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#isEmpty()
	 */
	@Override
	public boolean isEmpty() {
		return addrSet.isEmpty();
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#getMinAddress()
	 */
	@Override
	public Address getMinAddress() {
		return addrSet.getMinAddress();
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#getMaxAddress()
	 */
	@Override
	public Address getMaxAddress() {
		return addrSet.getMaxAddress();
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#getNumAddressRanges()
	 */
	@Override
	public int getNumAddressRanges() {
		return addrSet.getNumAddressRanges();
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#getAddressRanges()
	 */
	@Override
	public AddressRangeIterator getAddressRanges() {
		return addrSet.getAddressRanges();
	}

	@Override
	public Iterator<AddressRange> iterator() {
		return getAddressRanges();
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#getAddressRanges(boolean)
	 */
	@Override
	public AddressRangeIterator getAddressRanges(boolean startAtFront) {
		return addrSet.getAddressRanges(startAtFront);
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#getNumAddresses()
	 */
	@Override
	public long getNumAddresses() {
		return addrSet.getNumAddresses();
	}

	/**
	 * @see ghidra.program.model.address.AddressSetView#getAddresses(boolean)
	 */
	@Override
	public AddressIterator getAddresses(boolean forward) {
		return addrSet.getAddresses(forward);
	}

	@Override
	public AddressIterator getAddresses(Address start, boolean forward) {
		return addrSet.getAddresses(start, forward);
	}

	@Override
	public boolean intersects(AddressSetView set) {
		return addrSet.intersects(set);
	}

	@Override
	public boolean intersects(Address start, Address end) {
		return addrSet.intersects(start, end);
	}

	@Override
	public AddressSet intersect(AddressSetView set) {
		return addrSet.intersect(set);
	}

	@Override
	public AddressSet intersectRange(Address start, Address end) {
		return addrSet.intersectRange(start, end);
	}

	@Override
	public AddressSet union(AddressSetView set) {
		return addrSet.union(set);
	}

	@Override
	public AddressSet subtract(AddressSetView set) {
		return addrSet.subtract(set);
	}

	@Override
	public AddressSet xor(AddressSetView set) {
		return addrSet.xor(set);
	}

	@Override
	public boolean hasSameAddresses(AddressSetView set) {
		return addrSet.hasSameAddresses(set);
	}

	@Override
	public void removeBlock(MemoryBlock block, TaskMonitor monitor) throws LockException {
		lock.acquire();
		try {
			program.checkExclusiveAccess();

			if (!(block instanceof MemoryBlockDB)) {
				throw new IllegalArgumentException("Block not in program");
			}
			MemoryBlockDB memBlock = (MemoryBlockDB) block;
			if (blockMap.get(memBlock.getID()) != memBlock) {
				throw new IllegalArgumentException("Block not in program");
			}

			Address startAddress = block.getStart();

			program.setEventsEnabled(false);// ensure that no domain object change
			// events go out that would cause screen updates;
			// the code manager will be locked until the remove is done

			try {
				program.deleteAddressRange(startAddress, memBlock.getEnd(), monitor);
				memBlock.delete();
				init(true);
			}
			catch (IOException e) {
				program.dbError(e);
			}
			finally {
				program.setEventsEnabled(true);
			}
			fireBlockRemoved(startAddress);
			if (startAddress.getAddressSpace().isOverlaySpace()) {
				checkRemoveAddressSpace(startAddress.getAddressSpace());
			}
		}
		finally {
			lock.release();
		}
	}

	/**
	 * Tests if the given addressSpace (overlay space) is used by any blocks.  If not, it
	 * removes the space.
	 * @param addressSpace
	 */
	private void checkRemoveAddressSpace(AddressSpace addressSpace) {
		lock.acquire();
		try {
			program.removeOverlaySpace(addressSpace);
		}
		catch (LockException e) {
			throw new AssertException();
		}
		finally {
			lock.release();
		}

	}

	private void checkRange(Address start, long size)
			throws MemoryConflictException, AddressOverflowException {
		AddressSpace space = start.getAddressSpace();
		if (!space.isMemorySpace()) {
			throw new IllegalArgumentException(
				"Invalid memory address for block: " + start.toString(true));
		}
		AddressSpace mySpace = addrMap.getAddressFactory().getAddressSpace(space.getName());
		if (mySpace == null || !mySpace.equals(space)) {
			throw new IllegalArgumentException(
				"Block may not be created with unrecognized address space");
		}
		if (space.isOverlaySpace()) {
			throw new IllegalArgumentException("Block may not be created with an Overlay space");
		}
		if (size == 0) {
			throw new IllegalArgumentException("Block must have a non-zero length");
		}
		Address end = start.addNoWrap(size - 1);
		if (space == program.getAddressFactory().getDefaultAddressSpace()) {
			Address imageBase = addrMap.getImageBase();
			if (start.compareTo(imageBase) < 0 && end.compareTo(imageBase) >= 0) {
				throw new MemoryConflictException(
					"Block may not span image base address (" + imageBase + ")");
			}
		}
		if (addrSet.intersects(start, end)) {
			throw new MemoryConflictException(
				"Part of range (" + start + ", " + end + ") already exists in memory.");
		}
	}

	/**
	 * Gets the intersected set of addresses between a list of mapped memory blocks, and some other
	 * address set.
	 *
	 * @param mappedMemoryBlocks The mapped memory blocks to use in the intersection.
	 * @param otherAddrSet Some other address set to use in the intersection.
	 * @return The intersected set of addresses between 'mappedMemoryBlocks' and 'otherAddrSet'.
	 */
	private AddressSet getMappedIntersection(List<MappedMemoryBlock> mappedMemoryBlocks,
			AddressSet otherAddrSet) {
		AddressSet mappedIntersection = new AddressSet();
		for (MappedMemoryBlock mappedBlock : mappedMemoryBlocks) {
			AddressSet resolvedIntersection =
				otherAddrSet.intersect(new AddressSet(mappedBlock.getOverlayedAddressRange()));
			for (AddressRange resolvedRange : resolvedIntersection) {
				mappedIntersection.add(getMappedRange(mappedBlock, resolvedRange));
			}
		}

		return mappedIntersection;
	}

	private AddressRange getMappedRange(MappedMemoryBlock mappedBlock, AddressRange resolvedRange) {
		Address start, end;

		long startOffset =
			resolvedRange.getMinAddress().subtract(mappedBlock.getOverlayedMinAddress());
		boolean isBitMapped = mappedBlock.getType() == MemoryBlockType.BIT_MAPPED;
		if (isBitMapped) {
			start = mappedBlock.getStart().add(startOffset * 8);
			end = start.add((resolvedRange.getLength() * 8) - 1);
		}
		else {
			start = mappedBlock.getStart().add(startOffset);
			end = start.add(resolvedRange.getLength() - 1);
		}
		return new AddressRangeImpl(start, end);
	}

	@Override
	public void deleteAddressRange(Address startAddr, Address endAddr, TaskMonitor monitor)
			throws CancelledException {
		// never do anything here!!!
	}

	@Override
	public void moveAddressRange(Address fromAddr, Address toAddr, long length, TaskMonitor monitor)
			throws CancelledException {
		// never do anything here!!!
	}

	/**
	 * @see java.lang.Object#toString()
	 */
	@Override
	public final String toString() {
		lock.acquire();
		try {
			if (blocks == null || blocks.length == 0) {
				return "[empty]\n";
			}
			StringBuffer buffer = new StringBuffer();
			for (MemoryBlock block : blocks) {
				buffer.append("[");
				buffer.append(block.getStart());
				buffer.append(", ");
				buffer.append(block.getEnd());
				buffer.append("] ");
			}
			return buffer.toString();
		}
		finally {
			lock.release();
		}
	}

	public void overlayBlockRenamed(String oldName, String name)
			throws DuplicateNameException, LockException {
		program.renameOverlaySpace(oldName, name);

	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Memory) {
			return obj == this;
		}
		if (obj instanceof AddressSetView) {
			lock.acquire();
			try {
				return addrSet.equals(obj);
			}
			finally {
				lock.release();
			}
		}
		return false;
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}

	/* (non-Javadoc)
	 * @see ghidra.program.model.mem.Memory#getExecuteSet()
	 */
	@Override
	public AddressSetView getExecuteSet() {
		AddressSet set = new AddressSet();
		for (MemoryBlock block : blocks) {
			if (block.isExecute()) {
				set.addRange(block.getStart(), block.getEnd());
			}
		}
		return set;
	}

	@Override
	public void memoryChanged(Address addr, int size) {
		fireBytesChanged(addr, size);
	}

	@Override
	public AddressRangeIterator getAddressRanges(Address start, boolean forward) {
		return addrSet.getAddressRanges(start, forward);
	}

	@Override
	public AddressRange getFirstRange() {
		return addrSet.getFirstRange();
	}

	@Override
	public AddressRange getLastRange() {
		return addrSet.getLastRange();
	}

	@Override
	public AddressRange getRangeContaining(Address address) {
		return addrSet.getRangeContaining(address);
	}

	@Override
	public Iterator<AddressRange> iterator(boolean forward) {
		return addrSet.iterator(forward);
	}

	@Override
	public Iterator<AddressRange> iterator(Address start, boolean forward) {
		return addrSet.iterator(start, forward);
	}

	@Override
	public Address findFirstAddressInCommon(AddressSetView set) {
		return addrSet.findFirstAddressInCommon(set);
	}

	private void checkBlockSize(long newBlockLength, boolean initialized) {
		long limit = initialized ? MAX_INITIALIZED_BLOCK_SIZE : MAX_UNINITIALIZED_BLOCK_SIZE;
		if (newBlockLength > limit) {
			throw new IllegalStateException(
				"New memory block NOT added: exceeds the maximum memory block byte size of " +
					(limit >> GBYTE_SHIFT_FACTOR) + " GByte(s)");
		}

		long newSize = getNumAddresses() + newBlockLength;
		if (newSize < 0 || newSize > Memory.MAX_BINARY_SIZE) {
			throw new IllegalStateException(
				"New memory block NOT added: would cause total number of initialized program " +
					"bytes to exceed the maximum program size of " + MAX_BINARY_SIZE_GB +
					" GBytes");
		}
	}

}