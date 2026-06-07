import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const response = http.get(`${baseUrl}/actuator/health`);

  check(response, {
    'health endpoint is reachable': (res) => res.status === 200,
  });
}
