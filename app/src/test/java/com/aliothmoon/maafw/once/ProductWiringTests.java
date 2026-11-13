package com.aliothmoon.maafw.once;
import com.aliothmoon.maafw.readonly.UnboundReadOnlyController;
import com.aliothmoon.maafw.readonly.ReadOnlyRecognition;
public final class ProductWiringTests {
    private ProductWiringTests() { }
    public static void main(String[] args) {
        int checks=0;
        for(int sdk : new int[]{0,28,34,35,36}) {
            UnboundReadOnlyController controller=new UnboundReadOnlyController(sdk,"ranchu","google/sdk_gphone");
            OnceBackend.Result first=controller.backendSnapshot();
            for(int i=0;i<100;i++) {
                if(first.phase()!=OnceBackend.Phase.UNBOUND || first.admission()!=OnceBackend.Admission.NOT_ADMITTED
                    || first.owner()!=null || first.hit() || first.memory()!=OnceBackend.Memory.NOT_ALLOCATED)
                    throw new AssertionError("backend not inert");
                checks++;
                if(!controller.backendSnapshot().equals(first)) throw new AssertionError("state changed");
                checks++;
                controller.requestRecognition();
                if(controller.snapshot().authorization!=ReadOnlyRecognition.Authorization.NOT_AUTHORIZED
                    || controller.admission().backend!=ReadOnlyRecognition.Backend.UNBOUND)
                    throw new AssertionError("product enabled");
                checks++;
            }
        }
        System.out.println("PASS product wiring: "+checks+" assertions; no capture adapter or claim bound.");
    }
}