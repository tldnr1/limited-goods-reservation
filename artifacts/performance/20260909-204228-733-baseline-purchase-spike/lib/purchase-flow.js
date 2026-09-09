import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
const outcomes = new Counter('purchase_outcomes');
const unexpected = new Rate('unexpected_errors');
const acceptedLatency = new Trend('accepted_latency', true);
const rejectedLatency = new Trend('rejected_latency', true);
const base = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export function setup() {
  const response = http.post(base + '/api/sales', JSON.stringify({
    name: 'spike-' + Date.now(), opensAt: new Date(Date.now()-10000).toISOString(),
    items: [{ name:'goods',price:10000,total:1000,perUserLimit:1 }]
  }), {headers:{'Content-Type':'application/json'}});
  if(response.status !== 201) throw new Error('Seed failed: ' + response.status);
  return response.json();
}
export function purchase(sale) {
  const user = exec.scenario.name + '-' + exec.scenario.iterationInTest;
  const params = { headers:{'Content-Type':'application/json','X-User-Id':user,'Idempotency-Key':'purchase'},
                   timeout:'5s',tags:{name:'purchase'} };
  const response = http.post(base + '/api/purchases', JSON.stringify({
    saleId:sale.id,items:[{saleItemId:sale.items[0].id,quantity:1}]
  }), params);
  const outcome = response.status===201 ? 'held' : response.status===429 ? 'limited' :
    response.status===409 ? 'business_rejected' : 'unexpected';
  outcomes.add(1,{outcome,sale_id:sale.id});
  unexpected.add(![201,409,429].includes(response.status));
  (response.status===201 ? acceptedLatency : rejectedLatency).add(response.timings.duration);
  if(response.status===201) {
    params.headers['Idempotency-Key']='payment'; params.tags.name='payment_accept';
    const payment=http.post(base+'/api/orders/'+response.json('id')+'/payments',
      JSON.stringify({scenario:'SUCCESS'}),params);
    unexpected.add(payment.status!==202);
  }
}
export function teardown(sale) { console.log('Measured sale id: '+sale.id); }
