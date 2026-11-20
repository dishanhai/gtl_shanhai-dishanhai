import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ScriptableObject;
import java.nio.file.*;
class RhinoFlightSmoke {
 public static class Kit { public Cursor iterator(){ return new Cursor(); } }
 public static class Cursor {
  private int i; private final String[] ids={"boots","leggings","chestplate","helmet"};
  public boolean hasNext(){ return i<4; }
  public Item next(){ return new Item("kubejs:fermium_"+ids[i++]); }
 }
 public record Item(String id) {
  public String getId(){ return id; }
  public boolean isEmpty(){ return false; }
 }
 public static void main(String[] a)throws Exception {
  var c=Context.enter();var root=c.initStandardObjects();
  ScriptableObject.putProperty(root,"kit",Context.javaToJS(c,new Kit(),root),c);
  c.evaluateString(root,"""
    var receive;
    var global = {gtlPlayer:{armorSets:{magneto:["not_this"],spaceFermium:["not_this"],infinity:["not_this"],fermium:["fermium_boots","fermium_leggings","fermium_chestplate","fermium_helmet"]}}};
    var PlayerEvents={tick:function(){}};
    var EntityEvents={hurt:function(){}};
    var NetworkEvents={dataReceived:function(id,fn){if(id=="global.flyingspeedKey.consumeClick")receive=fn;}};
  ""","fixture",1,null);
  c.evaluateString(root,Files.readString(Path.of(a[0])), "armor_abilities.js",1,null);
  Object actual=c.evaluateString(root,"""
    var speed=.05;
    var player={abilities:{mayfly:true,getFlyingSpeed:function(){return speed;},setFlyingSpeed:function(v){speed=v;}},
      persistentData:{putInt:function(){}},isSteppingCarefully:function(){return false;},onUpdateAbilities:function(){},
      setStatusMessage:function(){},getArmorSlots:function(){return kit;}};
    receive({player:player}); speed;
  ""","flight_assertion",1,null);
  if(!(actual instanceof Number n)||Math.abs(n.doubleValue()-.1)>1e-6)throw new AssertionError(actual);
  System.out.println("KubeJS Rhino: first press reaches 0.1");
 }
}
