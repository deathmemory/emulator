package com.sun.jna;

import cn.banny.auxiliary.Inspector;
import cn.banny.emulator.Emulator;
import cn.banny.emulator.LibraryResolver;
import cn.banny.emulator.arm.ARMEmulator;
import cn.banny.emulator.arm.HookStatus;
import cn.banny.emulator.hook.ReplaceCallback;
import cn.banny.emulator.hook.hookzz.*;
import cn.banny.emulator.hook.whale.IWhale;
import cn.banny.emulator.hook.xhook.IxHook;
import cn.banny.emulator.hook.whale.Whale;
import cn.banny.emulator.hook.xhook.XHookImpl;
import cn.banny.emulator.linux.Module;
import cn.banny.emulator.linux.Symbol;
import cn.banny.emulator.linux.android.AndroidARMEmulator;
import cn.banny.emulator.linux.android.AndroidResolver;
import cn.banny.emulator.linux.android.dvm.*;
import cn.banny.emulator.memory.Memory;
import cn.banny.emulator.pointer.UnicornPointer;
import unicorn.ArmConst;
import unicorn.Unicorn;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class JniDispatch32 extends AbstractJni {

    private static LibraryResolver createLibraryResolver() {
        return new AndroidResolver(23);
    }

    private static ARMEmulator createARMEmulator() {
        return new AndroidARMEmulator("com.sun.jna");
    }

    private final ARMEmulator emulator;
    private final VM vm;
    private final Module module;

    private final DvmClass Native;

    private JniDispatch32() throws IOException {
        emulator = createARMEmulator();
        final Memory memory = emulator.getMemory();
        memory.setLibraryResolver(createLibraryResolver());
        memory.setCallInitFunction();

        vm = emulator.createDalvikVM(null);
        vm.setJni(this);
        DalvikModule dm = vm.loadLibrary(new File("src/test/resources/example_binaries/armeabi-v7a/libjnidispatch.so"), false);
        dm.callJNI_OnLoad(emulator);
        module = dm.getModule();

        Native = vm.resolveClass("com/sun/jna/Native");
    }

    private void destroy() throws IOException {
        emulator.close();
        System.out.println("destroy");
    }

    public static void main(String[] args) throws Exception {
        JniDispatch32 test = new JniDispatch32();

        test.test();

        test.destroy();
    }

    private void test() throws IOException {
        IxHook xHook = XHookImpl.getInstance(emulator);
        xHook.register("libjnidispatch.so", "malloc", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator emulator, long originFunction) {
                Unicorn unicorn = emulator.getUnicorn();
                int size = ((Number) unicorn.reg_read(ArmConst.UC_ARM_REG_R0)).intValue();
                System.out.println("malloc=" + size);
                return HookStatus.RET(unicorn, originFunction);
            }
        });
        xHook.refresh();

        IWhale whale = Whale.getInstance(emulator);
        Symbol free = emulator.getMemory().findModule("libc.so").findSymbolByName("free");
        whale.WInlineHookFunction(free, new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator emulator, long originFunction) {
                System.out.println("WInlineHookFunction free=" + UnicornPointer.register(emulator, ArmConst.UC_ARM_REG_R0));
                return HookStatus.RET(emulator.getUnicorn(), originFunction);
            }
        });

        long start = System.currentTimeMillis();
        final int size = 0x20;
        Number ret = Native.callStaticJniMethod(emulator, "malloc(J)J", size);
        Pointer pointer = UnicornPointer.pointer(emulator, ret.intValue() & 0xffffffffL);
        assert pointer != null;
        pointer.setString(0, getClass().getName());
        vm.deleteLocalRefs();
        Inspector.inspect(pointer.getByteArray(0, size), "malloc ret=" + ret + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        IHookZz hookZz = HookZz.getInstance(emulator);
        Symbol newJavaString = module.findSymbolByName("newJavaString");
        hookZz.wrap(newJavaString, new WrapCallback<Arm32RegisterContext>() {
            @Override
            public void preCall(Emulator emulator, Arm32RegisterContext ctx, HookEntryInfo info) {
                Pointer value = ctx.getR1Pointer();
                Pointer encoding = ctx.getR2Pointer();
                System.out.println("newJavaString value=" + value.getString(0) + ", encoding=" + encoding.getString(0));
            }
        });

        ret = Native.callStaticJniMethod(emulator, "getNativeVersion()Ljava/lang/String;");
        long hash = ret.intValue() & 0xffffffffL;
        StringObject version = vm.getObject(hash);
        vm.deleteLocalRefs();
        System.out.println("getNativeVersion version=" + version.getValue() + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        ret = Native.callStaticJniMethod(emulator, "getAPIChecksum()Ljava/lang/String;");
        hash = ret.intValue() & 0xffffffffL;
        StringObject checksum = vm.getObject(hash);
        vm.deleteLocalRefs();
        System.out.println("getAPIChecksum checksum=" + checksum.getValue() + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        ret = Native.callStaticJniMethod(emulator, "sizeof(I)I", 0);
        vm.deleteLocalRefs();
        System.out.println("sizeof POINTER_SIZE=" + ret.intValue() + ", offset=" + (System.currentTimeMillis() - start) + "ms");
    }

    @Override
    public DvmObject callStaticObjectMethod(VM vm, DvmClass dvmClass, String signature, String methodName, String args, Emulator emulator) {
        if ("java/lang/System->getProperty(Ljava/lang/String;)Ljava/lang/String;".equals(signature)) {
            UnicornPointer pointer = UnicornPointer.register(emulator, ArmConst.UC_ARM_REG_R3);
            StringObject string = vm.getObject(pointer.toUIntPeer());
            return new StringObject(vm, System.getProperty(string.getValue()));
        }

        return super.callStaticObjectMethod(vm, dvmClass, signature, methodName, args, emulator);
    }

    @Override
    public DvmObject newObject(DvmClass clazz, String signature, Emulator emulator) {
        switch (signature) {
            case "java/lang/String-><init>([B)V":
                UnicornPointer arrayPointer = UnicornPointer.register(emulator, ArmConst.UC_ARM_REG_R3);
                ByteArray array = vm.getObject(arrayPointer.toUIntPeer());
                return new StringObject(vm, new String(array.getValue()));
            case "java/lang/String-><init>([BLjava/lang/String;)V":
                arrayPointer = UnicornPointer.register(emulator, ArmConst.UC_ARM_REG_R3);
                array = vm.getObject(arrayPointer.toUIntPeer());
                UnicornPointer pointer = UnicornPointer.register(emulator, ArmConst.UC_ARM_REG_SP).getPointer(0);
                StringObject string = vm.getObject(pointer.toUIntPeer());
                try {
                    return new StringObject(vm, new String(array.getValue(), string.getValue()));
                } catch (UnsupportedEncodingException e) {
                    throw new IllegalStateException(e);
                }
        }
        return super.newObject(clazz, signature, emulator);
    }

    @Override
    public DvmObject getStaticObjectField(VM vm, DvmClass dvmClass, String signature) {
        switch (signature) {
            case "java/lang/Void->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Void");
            case "java/lang/Boolean->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Boolean");
            case "java/lang/Byte->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Byte");
            case "java/lang/Character->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Character");
            case "java/lang/Short->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Short");
            case "java/lang/Integer->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Integer");
            case "java/lang/Long->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Long");
            case "java/lang/Float->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Float");
            case "java/lang/Double->TYPE:Ljava/lang/Class;":
                return vm.resolveClass("java/lang/Double");
        }
        return super.getStaticObjectField(vm, dvmClass, signature);
    }

}
