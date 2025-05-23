package com.github.unidbg.ios;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Symbol;
import com.github.unidbg.hook.HookListener;
import com.sun.jna.Pointer;
import io.kaitai.struct.ByteBufferKaitaiStream;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class FixupChains {

    private static final Logger log = LoggerFactory.getLogger(FixupChains.class);

    // values for dyld_chained_fixups_header.imports_format
    static final int DYLD_CHAINED_IMPORT = 1;
    static final int DYLD_CHAINED_IMPORT_ADDEND = 2;
    static final int DYLD_CHAINED_IMPORT_ADDEND64 = 3;

    static final int DYLD_CHAINED_PTR_START_NONE = 0xffff; // used in page_start[] to denote a page with no fixups
    static final int DYLD_CHAINED_PTR_START_MULTI = 0x8000; // used in page_start[] to denote a page which has multiple starts
    static final int DYLD_CHAINED_PTR_START_LAST = 0x8000; // used in chain_starts[] to denote last start in list for page

    // values for dyld_chained_starts_in_segment.pointer_format
    static final int DYLD_CHAINED_PTR_ARM64E = 1; // stride 8, unauth target is vmaddr
    static final int DYLD_CHAINED_PTR_64 = 2; // target is vmaddr
    static final int DYLD_CHAINED_PTR_32 = 3;
    static final int DYLD_CHAINED_PTR_32_CACHE = 4;
    static final int DYLD_CHAINED_PTR_32_FIRMWARE = 5;
    static final int DYLD_CHAINED_PTR_64_OFFSET = 6; // target is vm offset
    static final int DYLD_CHAINED_PTR_ARM64E_OFFSET = 7; // stride 4, unauth target is vm offset
    static final int DYLD_CHAINED_PTR_64_KERNEL_CACHE = 8;
    static final int DYLD_CHAINED_PTR_ARM64E_USERLAND = 9; // stride 8, unauth target is vm offset
    static final int DYLD_CHAINED_PTR_ARM64E_FIRMWARE = 10; // stride 4, unauth target is vmaddr
    static final int DYLD_CHAINED_PTR_X86_64_KERNEL_CACHE = 11; // stride 1, x86_64 kernel caches
    static final int DYLD_CHAINED_PTR_ARM64E_USERLAND24 = 12; // stride 8, unauth target is vm offset, 24-bit bind

    static boolean greaterThanAddOrOverflow(long addLHS, long addRHS, long b) {
        return (addLHS > b) || (addRHS > (b-addLHS));
    }

    private static long signExtendedAddend(long addend) {
        long top8Bits = addend & 0x00007f80000L;
        long bottom19Bits = addend & 0x0000007ffffL;
        return (top8Bits << 13) | (((bottom19Bits << 37) >>> 37) & 0x00ffffffffffffffL);
    }

    static void handleChain(Emulator<?> emulator, MachOModule mm, List<HookListener> hookListeners, int pointer_format, Pointer chain, long raw64, List<BindTarget> bindTargets, ByteBufferKaitaiStream symbolsPool) {
        switch (pointer_format) {
            case DYLD_CHAINED_PTR_ARM64E:
            case DYLD_CHAINED_PTR_ARM64E_USERLAND24: {
                long dyld_chained_ptr_arm64e_auth_bind = chain.getLong(8);
                long dyld_chained_ptr_arm64e_rebase = chain.getLong(16);
                long dyld_chained_ptr_arm64e_bind = chain.getLong(24);
                long dyld_chained_ptr_arm64e_bind24 = chain.getLong(32);
                long dyld_chained_ptr_arm64e_auth_bind24 = chain.getLong(40);
                boolean authRebase_auth = (raw64 >>> 63) != 0;
                if (authRebase_auth) {
                    boolean authBind_bind = (dyld_chained_ptr_arm64e_auth_bind >>> 62) != 0;
                    if (authBind_bind) {
                        int authBind24_ordinal = (int) (dyld_chained_ptr_arm64e_auth_bind24 & 0xffffff);
                        int authBind_ordinal = (int) (dyld_chained_ptr_arm64e_auth_bind & 0xffff);
                        int bindOrdinal = (pointer_format == DYLD_CHAINED_PTR_ARM64E_USERLAND24) ? authBind24_ordinal : authBind_ordinal;
                        if ( bindOrdinal >= bindTargets.size() ) {
                            log.warn("authBind_bind out of range bind ordinal {} (max {}): pointer_format={}", bindOrdinal, bindTargets.size(), pointer_format);
                        } else {
                            // authenticated bind
                            /*BindTarget bindTarget = bindTargets.get(bindOrdinal);
                            newValue = (void*)(bindTargets[bindOrdinal]);
                            if (newValue != 0)  // Don't sign missing weak imports
                                newValue = (void*)fixupLoc->arm64e.signPointer(fixupLoc, (uintptr_t)newValue);*/
                            log.warn("Unsupported authenticated bind: bindOrdinal={}", bindOrdinal);
                        }
                    } else {
                        log.warn("Unsupported authenticated rebase");
                    }
                    break;
                } else {
                    boolean bind_bind = (dyld_chained_ptr_arm64e_bind >>> 62) != 0;
                    if (bind_bind) {
                        int bind24_ordinal = (int) (dyld_chained_ptr_arm64e_bind24 & 0xffffff);
                        int bind_ordinal = (int) (dyld_chained_ptr_arm64e_bind & 0xffff);
                        int bindOrdinal = (pointer_format == DYLD_CHAINED_PTR_ARM64E_USERLAND24) ? bind24_ordinal : bind_ordinal;
                        if (bindOrdinal >= bindTargets.size()) {
                            log.warn("bind_bind out of range bind ordinal {} (max {}): pointer_format={}", bindOrdinal, bindTargets.size(), pointer_format);
                        } else {
                            BindTarget bindTarget = bindTargets.get(bindOrdinal);
                            long addend19 = (dyld_chained_ptr_arm64e_bind >>> 32) & 0x7ffff;
                            if ((addend19 & 0x40000) != 0) {
                                addend19 |= 0xfffffffffffc0000L;
                            }
                            Fixup fixup = new Fixup(chain, addend19);
                            LazyFixup lazyFixup = bindTarget.bind(emulator, mm, hookListeners, symbolsPool, fixup);
                            if (lazyFixup != null) {
                                mm.unbindTargets.add(lazyFixup);
                            }
                        }
                        break;
                    } else {
                        if (pointer_format == DYLD_CHAINED_PTR_ARM64E) {
                            long target = dyld_chained_ptr_arm64e_rebase & 0xfffffffffL;
                            long high8 = (dyld_chained_ptr_arm64e_rebase >>> 36) & 0xff;

                            // plain rebase (old format target is vmaddr, new format target is offset)
                            long unpackedTarget = (high8 << 56) | target;
                            chain.setLong(0, unpackedTarget);
                            break;
                        } else {
                            log.warn("Unsupported DYLD_CHAINED_PTR_ARM64E");
                        }
                    }
                }
                throw new UnsupportedOperationException("DYLD_CHAINED_PTR_ARM64E dyld_chained_ptr_arm64e_auth_rebase=0x" + Long.toHexString(raw64) +
                        ", dyld_chained_ptr_arm64e_auth_bind=0x" + Long.toHexString(dyld_chained_ptr_arm64e_auth_bind));
            }
            case DYLD_CHAINED_PTR_64:
            case DYLD_CHAINED_PTR_64_OFFSET:
                boolean bind = (raw64 >>> 63) != 0;
                if (bind) {
                    int ordinal = (int) (raw64 & 0xffffff);
                    long addend = (raw64 >>> 24) & 0xff;
                    if (ordinal >= bindTargets.size()) {
                        throw new IllegalStateException(String.format("out of range bind ordinal %d (max %d)", ordinal, bindTargets.size()));
                    } else {
                        BindTarget bindTarget = bindTargets.get(ordinal);
                        Fixup fixup = new Fixup(chain, signExtendedAddend(addend));
                        LazyFixup lazyFixup = bindTarget.bind(emulator, mm, hookListeners, symbolsPool, fixup);
                        if (lazyFixup != null) {
                            mm.unbindTargets.add(lazyFixup);
                        }
                    }
                } else {
                    long target = raw64 & 0xfffffffffL;
                    long high8 = (raw64 >>> 36) & 0xff;

                    // plain rebase (old format target is vmaddr, new format target is offset)
                    long unpackedTarget = (high8 << 56) | target;
                    if (pointer_format == DYLD_CHAINED_PTR_64) {
                        chain.setLong(0, unpackedTarget + mm.slide);
                    } else {
                        chain.setLong(0, unpackedTarget + mm.machHeader);
                    }
                }
                break;
            default:
                throw new UnsupportedOperationException("pointer_format=" + pointer_format);
        }
    }

    static abstract class BindTarget implements MachO {
        final int libraryOrdinal;
        public BindTarget(int libraryOrdinal) {
            this.libraryOrdinal = libraryOrdinal;
        }
        abstract long resolve(Emulator<?> emulator, MachOModule mm, List<HookListener> hookListeners, String symbolName);
        public abstract LazyFixup bind(Emulator<?> emulator, MachOModule mm, List<HookListener> hookListeners, ByteBufferKaitaiStream symbolsPool, Fixup fixup);
        final Symbol resolveSymbol(MachOLoader loader, MachOModule mm, String symbolName, boolean weak) {
            if (libraryOrdinal > 0) {
                if (libraryOrdinal > mm.ordinalList.size()) {
                    throw new IllegalStateException("ordinal-too-large");
                }
                String path = mm.ordinalList.get(libraryOrdinal - 1);
                MachOModule targetImage = loader.path_modules.get(path);
                if (targetImage == null) {
                    targetImage = loader.modules.get(FilenameUtils.getName(path));
                }
                targetImage = loader.fakeTargetImage(targetImage, symbolName);
                if (targetImage == null && weak) {
                    return null;
                }
                if (targetImage == null) {
                    log.info("resolveSymbol libraryOrdinal={}, symbolName={}, path={}, module={}", libraryOrdinal, symbolName, path, mm.getPath());
                    return null;
                }
                return loader.findSymbolInternal(targetImage, symbolName);
            } else {
                switch (libraryOrdinal) {
                    case BIND_SPECIAL_DYLIB_SELF:
                        throw new UnsupportedOperationException("BIND_SPECIAL_DYLIB_SELF: symbolName=" + symbolName);
                    case BIND_SPECIAL_DYLIB_MAIN_EXECUTABLE:
                        throw new UnsupportedOperationException("BIND_SPECIAL_DYLIB_MAIN_EXECUTABLE: symbolName=" + symbolName);
                    case BIND_SPECIAL_DYLIB_FLAT_LOOKUP:
                        throw new UnsupportedOperationException("BIND_SPECIAL_DYLIB_FLAT_LOOKUP: symbolName=" + symbolName);
                    case BIND_SPECIAL_DYLIB_WEAK_LOOKUP: {
                        Symbol symbol = null;
                        for (MachOModule module : loader.modules.values().toArray(new MachOModule[0])) {
                            if (module.hasWeakDefines()) {
                                symbol = module.findSymbolByName(symbolName, false);
                                if (symbol != null) {
                                    break;
                                }
                            }
                        }
                        if (symbol == null && weak) {
                            return null;
                        }
                        if (symbol != null) {
                            return symbol;
                        }
                        if (log.isDebugEnabled()) {
                            MachOSymbol other = null;
                            MachOModule otherModule = null;
                            for (MachOModule module : loader.modules.values().toArray(new MachOModule[0])) {
                                MachOSymbol s = module.otherSymbols.get(symbolName);
                                if (s != null) {
                                    otherModule = module;
                                    other = s;
                                    break;
                                }
                            }
                            Logger log = LoggerFactory.getLogger(AbstractEmulator.class);
                            if (other != null && other.isExternalSymbol() && log.isTraceEnabled()) {
                                return new ExternalSymbol(symbolName, otherModule, other);
                            }
                            Symbol export = null;
                            for (MachOModule module : loader.modules.values().toArray(new MachOModule[0])) {
                                Symbol s = module.exportSymbols.get(symbolName);
                                if (export == null && s != null) {
                                    export = s;
                                }
                            }
                            FixupChains.log.info("BIND_SPECIAL_DYLIB_WEAK_LOOKUP: symbolName={}, module={}, other={}, export={}, otherModule={}", symbolName, mm.getPath(), other, export, otherModule);
                        }
                        return null;
                    }
                    default:
                        throw new UnsupportedOperationException("unknown-ordinal: symbolName=" + symbolName);
                }
            }
        }
    }

    static class dyld_chained_import_addend64 extends BindTarget {
        final boolean weak_import;
        final int name_offset;
        final long addend;
        public dyld_chained_import_addend64(int lib_ordinal, boolean weak_import, int name_offset, long addend) {
            super(lib_ordinal);
            this.weak_import = weak_import;
            this.name_offset = name_offset;
            this.addend = addend;
        }
        @Override
        long resolve(Emulator<?> emulator, MachOModule mm, List<HookListener> hookListeners, String symbolName) {
            MachOLoader loader = (MachOLoader) emulator.getMemory();
            Symbol symbol = resolveSymbol(loader, mm, symbolName, weak_import);
            if (symbol == null) {
                Logger log = LoggerFactory.getLogger(AbstractEmulator.class);
                if (log.isDebugEnabled()) {
                    String targetPath = null;
                    MachOModule targetImage = null;
                    if (libraryOrdinal > 0 && libraryOrdinal <= mm.ordinalList.size()) {
                        targetPath = mm.ordinalList.get(libraryOrdinal - 1);
                        targetImage = loader.path_modules.get(targetPath);
                    }
                    if (targetImage != null || weak_import) {
                        FixupChains.log.debug("bind mm={}, symbolName={}, lib_ordinal={}, weak_import={}, targetImage={}", mm.name, symbolName, libraryOrdinal, weak_import, targetImage);
                    } else {
                        FixupChains.log.info("bind mm={}, symbolName={}, lib_ordinal={}, weak_import={}, targetPath={}", mm.name, symbolName, libraryOrdinal, false, targetPath);
                    }
                }
                if (hookListeners == null) {
                    return 0L;
                }
                long bindAt = 0;
                for (HookListener listener : hookListeners) {
                    long hook = listener.hook(emulator.getSvcMemory(), mm.name, symbolName, HookListener.FIXUP_BIND);
                    if (hook > 0) {
                        bindAt = hook;
                        break;
                    }
                }
                return bindAt;
            }
            long bindAt = symbol.getAddress() + addend;
            if (hookListeners == null) {
                return bindAt;
            }
            for (HookListener listener : hookListeners) {
                long hook = listener.hook(emulator.getSvcMemory(), symbol.getModuleName(), symbol.getName(), bindAt);
                if (hook > 0) {
                    bindAt = hook;
                    break;
                }
            }
            return bindAt;
        }
        @Override
        public LazyFixup bind(Emulator<?> emulator, MachOModule mm, List<HookListener> hookListeners, ByteBufferKaitaiStream symbolsPool, Fixup fixup) {
            symbolsPool.seek(name_offset);
            String symbolName = new String(symbolsPool.readBytesTerm(0, false, true, true), StandardCharsets.US_ASCII);
            long bindAt = resolve(emulator, mm, hookListeners, symbolName);
            fixup.fix(bindAt);
            if (bindAt == 0 && libraryOrdinal > 0) {
                return new LazyFixup(mm, this, symbolName, fixup);
            } else {
                return null;
            }
        }
    }

    static class Fixup {
        private final Pointer chain;
        private final long addend;
        public Fixup(Pointer chain, long addend) {
            this.chain = chain;
            this.addend = addend;
        }
        final void fix(long bindAt) {
            long newValue = bindAt + addend;
            chain.setLong(0, newValue);
        }
    }

    static class LazyFixup {
        private final MachOModule mm;
        private final BindTarget bindTarget;
        private final String symbolName;
        private final Fixup fixup;
        LazyFixup(MachOModule mm, BindTarget bindTarget, String symbolName, Fixup fixup) {
            this.mm = mm;
            this.bindTarget = bindTarget;
            this.symbolName = symbolName;
            this.fixup = fixup;
        }
        boolean fixup(Emulator<?> emulator) {
            long bindAt = bindTarget.resolve(emulator, mm, null, symbolName);
            if (bindAt != 0) {
                fixup.fix(bindAt);
                return true;
            } else {
                return false;
            }
        }
    }

}
