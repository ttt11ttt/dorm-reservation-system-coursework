package cn.adc.dental;

import java.util.*;

/** Editorial content only; currently no first-party dentist photographs are approved. */
final class PublicProfilePolicy {
    private PublicProfilePolicy() {}
    private static final Set<String> APPROVED_AVATARS=Set.of();
    static boolean approvedAvatar(String key) {return key!=null&&APPROVED_AVATARS.contains(key);}
    static String avatarUrl(Object key) {return key instanceof String text&&approvedAvatar(text)?"/admin/assets/"+text:null;}
    static <T> T originalOrWritable(T original,boolean dentistActive,boolean publishing) {
        if(original!=null)return original;
        if(!dentistActive&&publishing)throw ApiError.conflict("RESOURCE_INACTIVE");
        return null;
    }
    private static String content(String value,int max) {
        String text=value==null?"":value.strip();
        if(text.length()>max||text.chars().anyMatch(Character::isISOControl)||text.indexOf('<')>=0||text.indexOf('>')>=0)throw ApiError.bad();return text;
    }
    static Map<String,Object> normalize(Integer version,String specialtyText,String titleText,String introText,String expertiseText,String avatarKey,Boolean published) {
        if(version==null||version<0||published==null)throw ApiError.bad();
        String specialty=content(specialtyText,80),title=content(titleText,80),intro=content(introText,2000),expertise=content(expertiseText,2000);
        if(published&&(specialty.isBlank()||title.isBlank()||intro.isBlank()||expertise.isBlank()))throw ApiError.bad();
        if(avatarKey!=null&&!approvedAvatar(avatarKey))throw ApiError.bad();
        var out=new LinkedHashMap<String,Object>();out.put("expectedRowVersion",version);out.put("specialtyLabel",specialty);out.put("professionalTitle",title);out.put("introduction",intro);out.put("expertise",expertise);out.put("avatarAssetKey",avatarKey);out.put("published",published);return out;
    }
}
