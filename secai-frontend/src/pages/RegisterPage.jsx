import { useState } from 'react';
import { Form, Input, Button, Card, Alert, Typography } from 'antd';
import { BankOutlined, MailOutlined, LockOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { register as registerApi } from '../api/auth';
import { useAuth } from '../context/AuthContext';

const { Title, Text } = Typography;

export default function RegisterPage() {
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const { login } = useAuth();
    const navigate = useNavigate();

    const handleSubmit = async (values) => {
        setLoading(true);
        setError('');
        try {
            const res = await registerApi({
                organizationName: values.organizationName,
                email: values.email,
                password: values.password,
            });
            login(res.data);          // auto-login after register
            navigate('/dashboard');
        } catch (err) {
            setError(err.response?.data?.error || 'Registration failed. Please try again.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div style={{
            minHeight: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: '#f5f5f5',
        }}>
            <Card style={{ width: 460, boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}>
                <div style={{ textAlign: 'center', marginBottom: 32 }}>
                    <Title level={3} style={{ margin: 0 }}>SecAI</Title>
                    <Text type="secondary">Create your organization account</Text>
                </div>

                {error && (
                    <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon />
                )}

                <Form layout="vertical" onFinish={handleSubmit} requiredMark={false}>
                    <Form.Item
                        label="Organization Name"
                        name="organizationName"
                        rules={[{ required: true, message: 'Organization name is required' }]}
                    >
                        <Input prefix={<BankOutlined />} placeholder="Acme Corp" size="large" />
                    </Form.Item>

                    <Form.Item
                        label="Work Email"
                        name="email"
                        rules={[{ required: true, type: 'email', message: 'Valid email required' }]}
                    >
                        <Input prefix={<MailOutlined />} placeholder="you@company.com" size="large" />
                    </Form.Item>

                    <Form.Item
                        label="Password"
                        name="password"
                        rules={[{ required: true, min: 8, message: 'Minimum 8 characters' }]}
                    >
                        <Input.Password prefix={<LockOutlined />} placeholder="Min. 8 characters" size="large" />
                    </Form.Item>

                    <Form.Item
                        label="Confirm Password"
                        name="confirmPassword"
                        dependencies={['password']}
                        rules={[
                            { required: true, message: 'Please confirm your password' },
                            ({ getFieldValue }) => ({
                                validator(_, value) {
                                    if (!value || getFieldValue('password') === value) return Promise.resolve();
                                    return Promise.reject(new Error('Passwords do not match'));
                                },
                            }),
                        ]}
                    >
                        <Input.Password prefix={<LockOutlined />} placeholder="Repeat password" size="large" />
                    </Form.Item>

                    <Form.Item>
                        <Button type="primary" htmlType="submit" block size="large" loading={loading}>
                            Create Account
                        </Button>
                    </Form.Item>

                    <div style={{ textAlign: 'center' }}>
                        <Text type="secondary">Already have an account? </Text>
                        <Link to="/login">Sign in</Link>
                    </div>
                </Form>
            </Card>
        </div>
    );
}