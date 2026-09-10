local t=redis.call('TIME')
local now=t[1]*1000+math.floor(t[2]/1000)
local mode,user,id,prefix=ARGV[1],ARGV[2],ARGV[3],ARGV[4]
local function result(state,expires,retry,data)
  return cjson.encode({state=state,expiresAt=expires or 0,retryAfter=retry or 0,data=data or ''})
end
local function catalog(sale)
  local value=redis.call('GET',prefix..'catalog:'..sale)
  if not value then return 'UNAVAILABLE' end
  local opening,state=string.match(value,'^(%d+):(.+)$')
  if not opening then return 'UNAVAILABLE' end
  if tonumber(opening)>now then return 'NOT_OPEN' end
  return state
end
redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now)
redis.call('ZREMRANGEBYSCORE',KEYS[3],'-inf',now)
-- Inactive browsers cannot block the head until the full waiting deadline.
for _,stale in ipairs(redis.call('ZRANGEBYSCORE',KEYS[5],'-inf',now,'LIMIT',0,100)) do
  redis.call('ZREM',KEYS[2],stale)
  redis.call('ZREM',KEYS[5],stale)
end
if mode=='join' then
  local old=redis.call('HGET',KEYS[1],'data')
  if old and old~=ARGV[5] then return result('CONFLICT') end
  local state=catalog(ARGV[6])
  if state=='UNAVAILABLE' or state=='NOT_OPEN' or state=='SOLD_OUT' then return result(state) end
  if old then
    local ready=tonumber(redis.call('HGET',KEYS[1],'ready') or '0')
    if (redis.call('HGET',KEYS[1],'state')=='READY' and ready<=now) or
       (redis.call('HGET',KEYS[1],'state')=='WAITING' and not redis.call('ZSCORE',KEYS[2],id)) then
      redis.call('DEL',KEYS[1])
      old=nil
    end
  end
  if not old then
    if redis.call('ZCARD',KEYS[2])+redis.call('ZCARD',KEYS[3])>=tonumber(ARGV[10]) then return result('FULL') end
    local deadline=now+tonumber(ARGV[7])
    redis.call('HSET',KEYS[1],'user',user,'data',ARGV[5],'sale',ARGV[6],'deadline',deadline,'state','WAITING','next',0)
    redis.call('PEXPIRE',KEYS[1],ARGV[7])
    redis.call('ZADD',KEYS[2],deadline,id)
    redis.call('ZADD',KEYS[5],now+30000,id)
  end
end
if redis.call('HGET',KEYS[1],'user')~=user then return result('NOT_FOUND') end
local data=redis.call('HGET',KEYS[1],'data')
local deadline=tonumber(redis.call('HGET',KEYS[1],'deadline'))
if redis.call('HGET',KEYS[1],'state')=='READY' then
  local ready=tonumber(redis.call('HGET',KEYS[1],'ready'))
  if ready>now then return result('READY',ready,0,data) end
  return result('EXPIRED')
end
if deadline<=now or not redis.call('ZSCORE',KEYS[2],id) then return result('EXPIRED') end
if mode=='join' then return result('WAITING',deadline,1) end
local nextPoll=tonumber(redis.call('HGET',KEYS[1],'next'))
if nextPoll>now then return result('WAITING',deadline,math.max(1,math.ceil((nextPoll-now)/1000))) end
redis.call('ZADD',KEYS[5],now+30000,id)
local state=catalog(redis.call('HGET',KEYS[1],'sale'))
if state=='UNAVAILABLE' then return result(state) end
if state=='SOLD_OUT' then
  redis.call('ZREM',KEYS[2],id)
  redis.call('ZREM',KEYS[5],id)
  return result(state)
end
if state=='FULLY_HELD' then
  redis.call('HSET',KEYS[1],'next',now+12000)
  return result('WAITING_FOR_INVENTORY_RETURN',deadline,12)
end
local rank=redis.call('ZRANK',KEYS[2],id)
local nextReady=tonumber(redis.call('GET',KEYS[4]) or '0')
if rank<tonumber(ARGV[9]) and redis.call('ZCARD',KEYS[3])<tonumber(ARGV[9]) and now>=nextReady then
  local ready=math.min(deadline,now+tonumber(ARGV[8]))
  redis.call('HSET',KEYS[1],'state','READY','ready',ready)
  redis.call('ZREM',KEYS[2],id)
  redis.call('ZREM',KEYS[5],id)
  redis.call('ZADD',KEYS[3],ready,id)
  redis.call('SET',KEYS[4],now+tonumber(ARGV[11]),'PX',tonumber(ARGV[11])+1000)
  return result('READY',ready,0,data)
end
local retry=rank<1000 and 1 or 12
redis.call('HSET',KEYS[1],'next',now+retry*1000)
return result('WAITING',deadline,retry)
