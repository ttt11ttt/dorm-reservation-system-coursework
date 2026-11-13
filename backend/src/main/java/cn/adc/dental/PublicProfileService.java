package cn.adc.dental;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Global editorial profile; never exposes dentist personnel fields. */
@Service
class PublicProfileService {
    record Edit(String operationId,Integer expectedRowVersion,String specialtyLabel,String professionalTitle,String introduction,String expertise,String avatarAssetKey,Boolean published) {
        @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); }
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Store db;
    private final Identity identity;
    PublicProfileService(Store db,Identity identity) {this.db=db;this.identity=identity;}
    private static Map<String,Object> normalized(Edit request) {
        if(request==null)throw ApiError.bad();
        var value=PublicProfilePolicy.normalize(request.expectedRowVersion(),request.specialtyLabel(),request.professionalTitle(),request.introduction(),request.expertise(),request.avatarAssetKey(),request.published());OrganizationRules.size(value);return value;
    }
    private Map<String,Object> snapshot(long id,String name,Map<String,Object> row) {
        var out=new LinkedHashMap<String,Object>();out.put("dentistId",Long.toString(id));out.put("name",name);out.put("exists",row!=null);
        out.put("specialtyLabel",row==null?"":row.get("specialty_label"));out.put("professionalTitle",row==null?"":row.get("professional_title"));
        out.put("introduction",row==null?"":row.get("introduction"));out.put("expertise",row==null?"":row.get("expertise"));
        out.put("avatarAssetKey",row==null?null:row.get("avatar_asset_key"));out.put("published",row!=null&&Store.enabled(row,"published"));
        out.put("rowVersion",row==null?0:((Number)row.get("row_version")).intValue());return out;
    }
    @Transactional
    public Object read(String header,long dentist) {
        var actor=identity.require(header);OrganizationRules.chainOnly(actor);
        var account=db.one("SELECT active,account_kind FROM accounts WHERE account_id=? FOR UPDATE",actor.id());
        if(!Store.enabled(account,"active")||!"员工".equals(account.get("account_kind")))throw ApiError.denied();
        identity.require(header);
        var grants=db.jdbc.queryForList("SELECT role_code,scope_kind,clinic_id,active FROM account_grants WHERE account_id=? ORDER BY grant_id LIMIT 513 FOR SHARE",actor.id());OrganizationRules.bound(grants.size(),512);
        OrganizationRules.chainOnly(new Identity.Actor(actor.id(),"员工",null,null,grants.stream().filter(g->Store.enabled(g,"active")).toList()));
        var d=db.one("SELECT name FROM dentists WHERE dentist_id=?",dentist);
        var rows=db.jdbc.queryForList("SELECT specialty_label,professional_title,introduction,expertise,avatar_asset_key,published,row_version FROM dentist_public_profiles WHERE dentist_id=?",dentist);
        db.audit(actor.id(),"读取","dentist_public_profile",dentist);
        return Store.json(snapshot(dentist,text(d.get("name")),rows.isEmpty()?null:rows.getFirst()));
    }
    private static String text(Object value) {return value==null?"":value.toString();}
    @SuppressWarnings("unchecked")
    private Object replay(long operation,long actor,long dentist,String hash) {
        var rows=db.jdbc.queryForList("SELECT actor_account_id,object_type,object_id,action,after_value FROM audit_logs WHERE audit_id=? FOR UPDATE",operation);
        if(rows.isEmpty())return null;
        var r=rows.getFirst();if(Store.number(r,"actor_account_id")!=actor||!"dentist_public_profile".equals(r.get("object_type"))||!Long.toString(dentist).equals(r.get("object_id"))||!"公开资料编辑".equals(r.get("action")))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        Map<String,Object> receipt;
        try {receipt=(Map<String,Object>)JSON.readValue(text(r.get("after_value")),Map.class);}catch(RuntimeException invalid){throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
        if(!hash.equals(receipt.get("requestFingerprint"))||!(receipt.get("result") instanceof Map<?,?> saved))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        var out=new LinkedHashMap<String,Object>();saved.forEach((k,v)->out.put(k.toString(),v));out.put("replayed",true);return Store.json(out);
    }
    @Transactional
    public Object edit(String header,long dentist,Edit request) {
        var fields=normalized(request);long operation=Store.parseId(request.operationId());
        var initial=identity.require(header);OrganizationRules.chainOnly(initial);
        var account=db.one("SELECT account_id,active,account_kind FROM accounts WHERE account_id=? FOR UPDATE",initial.id());
        if(!Store.enabled(account,"active")||!"员工".equals(account.get("account_kind")))throw ApiError.denied();
        identity.require(header); // Recheck credential/session after acquiring the account lock.
        var grants=db.jdbc.queryForList("SELECT role_code,scope_kind,clinic_id,active FROM account_grants WHERE account_id=? ORDER BY grant_id LIMIT 513 FOR SHARE",initial.id());OrganizationRules.bound(grants.size(),512);
        var active=grants.stream().filter(g->Store.enabled(g,"active")).toList();OrganizationRules.chainOnly(new Identity.Actor(initial.id(),"员工",null,null,active));
        var d=db.one("SELECT name,active FROM dentists WHERE dentist_id=? FOR UPDATE",dentist);
        String hash=OrganizationService.fingerprint("dentist_public_profile.edit.v1",new TreeMap<>(fields));
        var prior=PublicProfilePolicy.originalOrWritable(replay(operation,initial.id(),dentist,hash),Store.enabled(d,"active"),request.published());if(prior!=null)return prior;
        var rows=db.jdbc.queryForList("SELECT specialty_label,professional_title,introduction,expertise,avatar_asset_key,published,row_version FROM dentist_public_profiles WHERE dentist_id=? FOR UPDATE",dentist);
        var before=rows.isEmpty()?null:snapshot(dentist,text(d.get("name")),rows.getFirst());
        if(rows.isEmpty()) {
            if(request.expectedRowVersion()!=0)throw ApiError.conflict("ROW_VERSION_CONFLICT");
            db.jdbc.update("INSERT INTO dentist_public_profiles(dentist_id,specialty_label,professional_title,introduction,expertise,avatar_asset_key,published,row_version,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,1,?,?)",dentist,fields.get("specialtyLabel"),fields.get("professionalTitle"),fields.get("introduction"),fields.get("expertise"),fields.get("avatarAssetKey"),fields.get("published"),initial.id(),db.now());
        } else {
            int version=((Number)rows.getFirst().get("row_version")).intValue();if(version!=request.expectedRowVersion()||version==Integer.MAX_VALUE)throw ApiError.conflict("ROW_VERSION_CONFLICT");
            int changed=db.jdbc.update("UPDATE dentist_public_profiles SET specialty_label=?,professional_title=?,introduction=?,expertise=?,avatar_asset_key=?,published=?,row_version=row_version+1,updated_by=?,updated_at=? WHERE dentist_id=? AND row_version=?",fields.get("specialtyLabel"),fields.get("professionalTitle"),fields.get("introduction"),fields.get("expertise"),fields.get("avatarAssetKey"),fields.get("published"),initial.id(),db.now(),dentist,version);
            if(changed!=1)throw ApiError.conflict("ROW_VERSION_CONFLICT");
        }
        var afterRow=db.one("SELECT specialty_label,professional_title,introduction,expertise,avatar_asset_key,published,row_version FROM dentist_public_profiles WHERE dentist_id=?",dentist);
        var after=snapshot(dentist,text(d.get("name")),afterRow);var result=Map.of("replayed",false,"resource",after);
        var receipt=Map.of("requestFingerprint",hash,"result",result);OrganizationRules.size(receipt);
        try {db.jdbc.update("INSERT INTO audit_logs(audit_id,actor_account_id,action,object_type,object_id,before_value,after_value,happened_at) VALUES(?,?,'公开资料编辑','dentist_public_profile',?,?,?,?)",operation,initial.id(),Long.toString(dentist),before==null?null:Store.encode(before),Store.encode(receipt),db.now());}
        catch(DataIntegrityViolationException collision) {throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
        return Store.json(result);
    }
}
