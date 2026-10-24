package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class SetupFlowTest {
    static int assertions;
    static void check(boolean yes,String fixture,String why){assertions++;if(!yes)throw new AssertionError(fixture+": "+why);}
    @SuppressWarnings("unchecked") static Object cloneValue(Object value){if(value instanceof Map){Map<String,Object> m=new LinkedHashMap<>();((Map<String,Object>)value).forEach((k,v)->m.put(k,cloneValue(v)));return m;}if(value instanceof List){List<Object> l=new ArrayList<>();for(Object v:(List<?>)value)l.add(cloneValue(v));return l;}return value;}
    static void patch(Map<String,Object> root,String path,Object value){String[] keys=path.split("\\.");Map<String,Object> node=root;for(int i=0;i<keys.length-1;i++){Object old=node.get(keys[i]);if(!(old instanceof Map)){old=new LinkedHashMap<String,Object>();node.put(keys[i],old);}node=SetupFlow.map(old);}node.put(keys[keys.length-1],cloneValue(value));}
    public static void main(String[] args)throws Exception{
        String text=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);Map<String,Object> fixture=SetupFlow.map(Json.parse(text));Map<String,Object> base=SetupFlow.map(fixture.get("base"));int cases=0;
        for(Object raw:(List<?>)fixture.get("cases")){
            Map<String,Object> test=SetupFlow.map(raw);String name=(String)test.get("name");Map<String,Object> input=SetupFlow.map(cloneValue(base));SetupFlow.map(test.get("changes")).forEach((k,v)->patch(input,k,v));SetupFlow.View view=SetupFlow.evaluate(input);cases++;
            check(view.steps.size()==5,name,"five ordered checks");String[] order={"storage","install","loaders","script","data"};SetupFlow.Step target=null;
            for(int i=0;i<5;i++){SetupFlow.Step step=view.steps.get(i);check(step.id.equals(order[i]),name,"order");check(step.action!=null&&step.messageKey.matches("[a-z_]+"),name,"fixed message key and one action");if(step.id.equals(test.get("step")))target=step;}
            check(target!=null,name,"target exists");check(target.state.name().equals(test.get("state")),name,"state");check(target.messageKey.equals(test.get("key")),name,"message");check(target.action.name().equals(test.get("action")),name,"action");
            check(Objects.equals(view.firstBlocker==null?null:view.firstBlocker.id,test.get("first")),name,"first known missing, else unknown");
            if(test.containsKey("complete"))check(view.complete==Boolean.TRUE.equals(test.get("complete")),name,"complete");else check(!view.complete,name,"not complete");
            if(test.containsKey("onboarding"))check(view.needsOnboarding==Boolean.TRUE.equals(test.get("onboarding")),name,"onboarding latch");
            if(test.containsKey("persist"))check(view.shouldPersistCompletion==Boolean.TRUE.equals(test.get("persist")),name,"persist after green");
            if(view.complete)check(view.firstBlocker==null&&!view.needsOnboarding,name,"complete invariant");
            System.out.println("PASS "+name);
        }
        SetupFlow.View unknown=SetupFlow.evaluate(Collections.emptyMap());check(!unknown.complete,"no evidence","not ready");check(unknown.firstBlocker.state==SetupFlow.State.UNKNOWN,"no evidence","absence not missing loader");
        SetupFlow.View malformed=SetupFlow.evaluate(Collections.singletonMap("platform","wrong type"));check(!malformed.complete,"malformed map","unknown fields do not make ready");
        System.out.println("{\"fixtures\":"+cases+",\"assertions\":"+assertions+",\"failed\":0,\"deviceAccess\":false,\"networkAccess\":false,\"publicEdits\":false}");
    }
}
