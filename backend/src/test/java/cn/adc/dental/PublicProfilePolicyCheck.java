package cn.adc.dental;

/** Pure memory validation of public editorial boundaries. */
public final class PublicProfilePolicyCheck {
    private static int checks;
    private static void yes(boolean value) {checks++;if(!value)throw new AssertionError("profile check "+checks);}
    private static void bad(Runnable action) {try{action.run();throw new AssertionError("expected rejection");}catch(ApiError error){yes(error.status==400);}}
    public static void main(String[] args) {
        var good=PublicProfilePolicy.normalize(0," 牙周 ","医师","简介","擅长",null,true);
        yes("牙周".equals(good.get("specialtyLabel")));
        yes(PublicProfilePolicy.avatarUrl(null)==null);
        bad(()->PublicProfilePolicy.normalize(0,"","医师","简介","擅长",null,true));
        bad(()->PublicProfilePolicy.normalize(0,"牙周","医师","<script>","擅长",null,true));
        bad(()->PublicProfilePolicy.normalize(0,"牙周","医师","简介","擅长","https://example.com/photo",true));
        bad(()->PublicProfilePolicy.normalize(-1,"牙周","医师","简介","擅长",null,false));
        yes(!PublicProfilePolicy.approvedAvatar("unreviewed.png"));
        Object receipt=new Object();yes(PublicProfilePolicy.originalOrWritable(receipt,false,true)==receipt);
        badConflict(()->PublicProfilePolicy.originalOrWritable(null,false,true));
        yes(PublicProfilePolicy.originalOrWritable(null,false,false)==null);
        System.out.println("PublicProfilePolicyCheck: "+checks+" passed (pure memory only)");
    }
    private static void badConflict(Runnable action) {try{action.run();throw new AssertionError("expected conflict");}catch(ApiError error){yes(error.status==409&&"RESOURCE_INACTIVE".equals(error.code));}}
}
